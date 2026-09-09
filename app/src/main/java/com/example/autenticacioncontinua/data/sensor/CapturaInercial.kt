package com.example.autenticacioncontinua.data.sensor

import android.util.Log
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
import com.example.autenticacioncontinua.domain.sensor.IFuenteSensor
import com.example.autenticacioncontinua.domain.sensor.ProveedorDeConfiguracion
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
    /**
     * Qué configuración de sensores está activa.
     *
     * Decide QUÉ SE REGISTRA, que es lo que de verdad separa un nivel del
     * diseño de otro en el eje de recursos: el coste eléctrico de un sensor lo
     * paga el aparato mientras está encendido, se procesen sus datos o no.
     * Filtrar los canales después de haberlos capturado mediría siempre el
     * coste de la configuración más rica.
     *
     * Por defecto, la configuración desplegada, para que las pruebas y la
     * recogida ambiental no tengan que enterarse.
     */
    private val configuracion: ProveedorDeConfiguracion =
        ProveedorDeConfiguracion { ConfiguracionSensores.POR_DEFECTO },
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

    /**
     * Las fuentes que ESTA captura arrancó de verdad.
     *
     * POR QUÉ SE RECUERDAN EN VEZ DE RECALCULARLAS AL PARAR. Porque la
     * configuración activa puede cambiar entre el arranque y la parada —es
     * estado de protocolo y el investigador la conmuta entre bloques—, y
     * entonces `fuentes()` devolvería un conjunto distinto del que se registró.
     * Los sensores que sobraran quedarían REGISTRADOS EN EL SENSOR MANAGER
     * después de detener la captura: sin nadie consumiendo su flujo, pero
     * despertando el aparato y gastando la batería que este módulo existe para
     * medir. Y no fallaría nada: la captura terminaría bien y el resumen
     * saldría correcto.
     */
    private var fuentesArrancadas: List<IFuenteSensor> = emptyList()
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

        fuentesArrancadas = fuentes()
        for (f in fuentesArrancadas) f.iniciar()

        // Un solo consumidor de las tres corrientes: el alineador NO es seguro
        // entre hilos y no hace falta que lo sea si todo lo que le llega pasa
        // por aquí.
        trabajoColector = alcance.launch {
            merge(*fuentesArrancadas.map { it.flujo() }.toTypedArray())
                .collect { muestra ->
                    ali.aceptar(muestra)?.let { fila ->
                        val ok = ch.trySend(fila).isSuccess
                        if (!ok) perdidas++
                    }
                }
        }
        trabajoEscritor = alcance.launch { vaciar(ch) }
        Log.i(TAG, "captura iniciada en el bloque $bloqueId; " +
            "config=${configuracion.activa().clave} " +
            "sensores=${fuentesArrancadas.joinToString { it.tipo.clave }}; " +
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

        // Lo ARRANCADO, no lo que pida la configuración ahora. Ver
        // `fuentesArrancadas`.
        for (f in fuentesArrancadas) f.detener()
        fuentesArrancadas = emptyList()
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

    /**
     * Las fuentes que hay que registrar: las que PIDE la configuración activa y
     * además EXISTEN en este terminal.
     *
     * Los dos filtros son distintos y hacen falta los dos. La configuración
     * dice qué quiere el diseño; la disponibilidad, qué tiene el aparato. Un
     * móvil sin magnetómetro con la configuración D registra dos sensores en
     * vez de tres, y eso queda en `magnetometroDisponible` del resumen para que
     * el análisis pueda excluir ese bloque en lugar de tratarlo como si tuviera
     * los tres canales.
     */
    private fun fuentes() = listOf(acelerometro, giroscopio, magnetometro)
        .filter { configuracion.activa().requiere(it.tipo) }
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
