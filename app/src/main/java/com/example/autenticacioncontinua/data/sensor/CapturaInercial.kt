package com.example.autenticacioncontinua.data.sensor

import android.util.Log
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.sensor.IFuenteSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cómo fue la captura de un bloque. Va al informe y a la sección de límites. */
data class ResumenCaptura(
    val bloqueId: Long,
    val filasEscritas: Long,
    val descartadasSinGiroscopio: Long,
    val conGiroscopioRancio: Long,
    val magnetometroDisponible: Boolean,
    val perdidasEnCola: Long
) {
    /** Fracción de muestras con el giroscopio fuera de tolerancia. */
    val fraccionRancia: Double
        get() = if (filasEscritas > 0) conGiroscopioRancio.toDouble() / filasEscritas else 0.0
}

/**
 * Captura los tres sensores durante un bloque y los escribe por lotes.
 *
 * SEPARA RECIBIR DE ESCRIBIR, y esa es toda la clase. Las muestras llegan en el
 * hilo del sensor, cien veces por segundo; escribir en SQLite desde ahí
 * bloquearía ese hilo en cada `fsync` y retrasaría todas las entregas
 * posteriores, corrompiendo la cadencia de la serie que se está midiendo. Aquí
 * el hilo del sensor sólo alinea y encola; una corrutina aparte vacía la cola en
 * lotes de [MuestraInercialEntity.LOTE].
 *
 * LA COLA TIENE FONDO Y CUENTA LO QUE PIERDE. Con `DROP_OLDEST` y capacidad
 * acotada, un escritor atascado pierde muestras viejas en vez de acumular
 * memoria hasta que el sistema mate el proceso a mitad de sesión. Lo que se
 * pierde se cuenta y sale en [ResumenCaptura]: una captura con pérdidas es un
 * dato con una limitación conocida, no un dato roto en silencio.
 */
class CapturaInercial(
    private val acelerometro: IFuenteSensor,
    private val giroscopio: IFuenteSensor,
    private val magnetometro: IFuenteSensor,
    private val repositorio: ISesionControladaRepository,
    private val alcance: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * El colector y el escritor son trabajos SEPARADOS, y eso es lo que permite
     * cerrar bien.
     *
     * Estuvieron juntos bajo un solo `Job` hasta el 30/08, y cancelarlo mataba
     * a los dos: el escritor moría con su lote a medio llenar y esas filas se
     * perdían. Como sólo escribe al juntar [MuestraInercialEntity.LOTE], en un
     * bloque real se iban hasta 499 muestras —los últimos segundos de cada
     * bloque— sin que nada fallara. Ahora se cancela SÓLO el colector, se cierra
     * la cola y se espera al escritor, que termina su bucle al vaciarla y
     * escribe lo que le quede.
     */
    private var trabajoColector: Job? = null
    private var trabajoEscritor: Job? = null
    private var alineador: AlineadorInercial? = null
    private var cola: Channel<MuestraInercialEntity>? = null

    @Volatile private var escritas = 0L
    private var bloqueActual = 0L
    @Volatile private var perdidas = 0L

    val estaCapturando: Boolean get() = trabajoColector?.isActive == true

    fun iniciar(bloqueId: Long) {
        check(!estaCapturando) { "ya hay una captura en curso" }
        val ali = AlineadorInercial(bloqueId)
        val ch = Channel<MuestraInercialEntity>(
            capacity = CAPACIDAD_COLA,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        alineador = ali
        cola = ch
        bloqueActual = bloqueId
        escritas = 0
        perdidas = 0

        for (f in fuentes()) f.iniciar()

        // Un solo consumidor de las tres corrientes: el alineador NO es seguro
        // entre hilos y no hace falta que lo sea si todo lo que le llega pasa
        // por aquí.
        trabajoColector = alcance.launch {
            merge(acelerometro.flujo(), giroscopio.flujo(), magnetometro.flujo())
                .collect { muestra ->
                    ali.aceptar(muestra)?.let { fila ->
                        val ok = ch.trySend(fila).isSuccess
                        if (!ok) perdidas++
                    }
                }
        }
        trabajoEscritor = alcance.launch { vaciar(ch) }
        Log.i(TAG, "captura iniciada en el bloque $bloqueId; " +
            "magnetometro=${magnetometro.disponible}")
    }

    /**
     * Para los sensores, vacía lo que quede en la cola y devuelve el resumen.
     *
     * EL ORDEN ES TODO EL MÉTODO:
     *
     *  1. se paran los sensores, para que no entren muestras nuevas;
     *  2. se cancela SÓLO el colector — el escritor no, o su lote a medio
     *     llenar se iría con él;
     *  3. se cierra la cola, con lo que el `for (fila in ch)` del escritor
     *     termina en cuanto la vacía;
     *  4. se le espera. Ahí es donde escribe el último lote parcial, que son
     *     los últimos segundos de tecleo del participante y no valen menos que
     *     los demás.
     *
     * El paso 4 va en [NonCancellable] porque `detener` se llama desde el cierre
     * del bloque, que puede venir de una cancelación.
     */
    suspend fun detener(): ResumenCaptura? {
        val ali = alineador ?: return null
        val ch = cola ?: return null

        for (f in fuentes()) f.detener()
        trabajoColector?.cancel()
        trabajoColector = null

        withContext(NonCancellable) {
            ch.close()
            trabajoEscritor?.join()
        }
        trabajoEscritor = null
        alineador = null
        cola = null

        val resumen = ResumenCaptura(
            bloqueId = bloqueActual,
            filasEscritas = escritas,
            descartadasSinGiroscopio = ali.descartadasSinGiroscopio,
            conGiroscopioRancio = ali.conGiroscopioRancio,
            magnetometroDisponible = magnetometro.disponible,
            perdidasEnCola = perdidas
        )
        Log.i(TAG, "captura detenida: $resumen")
        return resumen
    }

    private suspend fun vaciar(ch: Channel<MuestraInercialEntity>) {
        val lote = ArrayList<MuestraInercialEntity>(MuestraInercialEntity.LOTE)
        for (fila in ch) {
            lote += fila
            if (lote.size >= MuestraInercialEntity.LOTE) {
                repositorio.guardarMuestras(lote.toList())
                escritas += lote.size
                lote.clear()
            }
        }
        if (lote.isNotEmpty()) {
            repositorio.guardarMuestras(lote.toList())
            escritas += lote.size
        }
    }

    private fun fuentes() = listOf(acelerometro, giroscopio, magnetometro)
        .filter { it.disponible }

    companion object {
        private const val TAG = "CapturaInercial"

        /**
         * Diez segundos de margen a 100 Hz.
         *
         * El escritor hace una transacción cada 500 filas, o sea cada cinco
         * segundos; con este fondo aguanta dos transacciones lentas seguidas
         * sin perder nada, y sigue acotando la memoria si algo se atasca de
         * verdad.
         */
        const val CAPACIDAD_COLA = 1_000
    }
}
