package com.example.autenticacioncontinua.data.ml

import android.util.Log
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.domain.ml.RasterizadoTactil
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Convierte los bloques del corpus CONTROLADO en ventanas para el modelo.
 *
 * POR QUÉ EXISTE, SIENDO QUE YA HAY UN `WindowSegmenter`. Aquel lee las tablas
 * de campo (`accelerometer_data`, `gyroscope_data`), produce exactamente seis
 * canales y lo exige en su `init`. Sirve para el uso libre y ahí se queda. Pero
 * el estudio es la sesión controlada, y hasta ahora `muestras_inerciales` y
 * `eventos_tecleo` NO tenían ningún camino hacia el pipeline de aprendizaje:
 * se capturaban y se quedaban en la base. Sin esta clase, la campaña de
 * efectividad de la hipótesis P1 no puede ejecutarse.
 *
 * TRES DIFERENCIAS DE FONDO CON EL SEGMENTADOR DE CAMPO:
 *
 *  1. EL NÚMERO DE CANALES LO DECIDE LA CONFIGURACIÓN ACTIVA, no una constante.
 *     A produce 3, B produce 6, C y D producen 9. Es la variable independiente
 *     del estudio; fijarla en el código haría el estudio imposible.
 *
 *  2. LA SESIÓN LA DA LA ESTRUCTURA, NO UN HUECO TEMPORAL. En campo hay que
 *     adivinar dónde acaba una sesión mirando huecos de 30 s. Aquí un bloque es
 *     un bloque: tiene identificador, principio y fin. Eso importa porque la
 *     partición train/val/test se hace POR SESIÓN, y adivinarla mal es fuga.
 *
 *  3. HAY DOS RELOJES Y SE USAN LOS DOS. Ver [rejillaDe].
 *
 * NO ESCALA LA SALIDA. Devuelve ventanas en unidades físicas. El escalador
 * depende de la configuración —cada una tiene su `scaler_stats.json`— y esa
 * plomería es de quien cargue los artefactos, no de aquí.
 */
class SegmentadorControlado(
    private val repositorio: ISesionControladaRepository,
    private val windowSize: Int = TAMANO_VENTANA,
    private val step: Int = PASO,
    private val hz: Int = HZ
) {

    /**
     * Ventanas de todos los bloques de [sesionId] bajo [configuracion].
     *
     * Los bloques marcados como interrumpidos SÍ entran: un bloque de 3 min 20 s
     * es señal válida, sólo que menos. Lo que no puede pasar es que se cuele sin
     * que el análisis sepa que lo fue, y para eso está la marca en la propia
     * fila del bloque.
     */
    suspend fun ventanasDe(
        sesionId: Long,
        configuracion: ConfiguracionSensores
    ): List<SensorWindow> = withContext(Dispatchers.IO) {
        val salida = ArrayList<SensorWindow>()
        for (bloque in repositorio.bloquesDe(sesionId)) {
            salida += ventanasDeBloque(bloque.id, bloque.indice, configuracion)
        }
        Log.i(TAG, "sesion $sesionId: ${salida.size} ventanas de ${configuracion.clave}")
        salida
    }

    /** Ventanas de un solo bloque. `sessionId` de la ventana = [indiceBloque]. */
    suspend fun ventanasDeBloque(
        bloqueId: Long,
        indiceBloque: Int,
        configuracion: ConfiguracionSensores
    ): List<SensorWindow> = withContext(Dispatchers.IO) {
        val muestras = repositorio.muestrasDe(bloqueId)
        if (muestras.size < windowSize) {
            Log.i(TAG, "bloque $bloqueId: ${muestras.size} muestras, insuficientes")
            return@withContext emptyList()
        }
        if (configuracion.requiere(com.example.autenticacioncontinua.domain.sensor.TipoSensor.MAGNETOMETRO) &&
            muestras.any { it.magX == null }
        ) {
            // La columna es nulable porque el magnetómetro puede no estar. Un
            // null NO se puede rellenar con cero: cero es un campo magnético
            // físicamente imposible y el modelo lo leería como una medida.
            Log.w(TAG, "bloque $bloqueId: faltan lecturas de magnetómetro; " +
                "no se puede segmentar en ${configuracion.clave}")
            return@withContext emptyList()
        }

        val rejilla = rejillaDe(muestras)
        if (rejilla.size < windowSize) return@withContext emptyList()

        val pulsaciones = if (configuracion.incluyeTactil) {
            val desfase = desfaseParedAMonotono(muestras)
            repositorio.eventosDe(bloqueId).map {
                RasterizadoTactil.Pulsacion(
                    tDown = it.tDownMs * 1_000_000L + desfase,
                    tUp = if (it.tUpMs > it.tDownMs) it.tUpMs * 1_000_000L + desfase else 0L,
                    x = it.x,
                    y = it.y
                )
            }
        } else {
            emptyList()
        }

        val canales = construirCanales(muestras, rejilla, configuracion, pulsaciones)
        val nCanales = canales.size

        // El primer instante de pared de la rejilla, para poder fechar cada
        // ventana en el mismo reloj en el que se fecha todo lo demás.
        val paredInicio = muestras.first().tParedMs
        val monotonoInicio = muestras.first().tMonotonoNs

        val salida = ArrayList<SensorWindow>()
        var inicio = 0
        while (inicio + windowSize <= rejilla.size) {
            val plano = FloatArray(windowSize * nCanales)
            for (t in 0 until windowSize) {
                for (c in 0 until nCanales) {
                    plano[t * nCanales + c] = canales[c][inicio + t]
                }
            }
            val desplazamientoMs = (rejilla[inicio] - monotonoInicio) / 1_000_000L
            salida.add(
                SensorWindow(
                    values = plano,
                    sessionId = indiceBloque,
                    startTimestampMs = paredInicio + desplazamientoMs
                )
            )
            inicio += step
        }
        salida
    }

    /**
     * Rejilla regular a [hz], en NANOSEGUNDOS MONÓTONOS.
     *
     * POR QUÉ EL RELOJ MONÓTONO Y NO EL DE PARED. `MuestraInercialEntity` guarda
     * los dos a propósito. El de pared puede saltar hacia atrás en mitad de un
     * bloque si el sistema sincroniza la hora, y un salto convierte la
     * interpolación en señal inventada sin que nada falle. El monótono no
     * salta. El de pared se usa sólo para FECHAR la ventana, que es para lo que
     * sirve.
     *
     * La captura ya apunta a 50 Hz, así que esto no está remuestreando de 100 a
     * 50: está corrigiendo el JITTER del muestreo del sistema, que entrega las
     * lecturas cuando puede y no cuando toca.
     */
    private fun rejillaDe(muestras: List<MuestraInercialEntity>): LongArray {
        val t0 = muestras.first().tMonotonoNs
        val t1 = muestras.last().tMonotonoNs
        val duracionNs = t1 - t0
        if (duracionNs <= 0) return LongArray(0)
        val n = (duracionNs * hz / 1_000_000_000L).toInt()
        if (n < windowSize) return LongArray(0)
        val paso = duracionNs.toDouble() / (n - 1)
        return LongArray(n) { t0 + (it * paso).toLong() }
    }

    /**
     * Cuánto hay que sumar a un instante de pared en ns para llevarlo al reloj
     * monótono, en el que vive la rejilla.
     *
     * POR QUÉ LA MEDIANA Y NO LA PRIMERA MUESTRA. Los dos relojes avanzan al
     * mismo ritmo, así que el desfase es constante y con una muestra bastaría
     * —si el reloj de pared no saltara nunca—. Salta: una sincronización de
     * hora a mitad de bloque mueve `tParedMs` y no `tMonotonoNs`. Anclando en
     * la primera muestra, un salto desplaza TODAS las pulsaciones; con la
     * mediana, sólo se desplazan si el salto afecta a más de la mitad del
     * bloque. Es la diferencia entre un canal táctil desalineado y uno
     * ligeramente ruidoso.
     *
     * Las pulsaciones sólo traen reloj de pared (`EventoTecleoEntity.tDownMs`),
     * así que esta conversión es obligatoria para poder rasterizarlas sobre una
     * rejilla monótona. Es el mismo problema que en HMOG, donde alinear el
     * táctil por el reloj equivocado daba tramos de contacto de 332 ms en vez
     * de 53 y un ciclo de trabajo del 70% en vez del 6%.
     */
    private fun desfaseParedAMonotono(muestras: List<MuestraInercialEntity>): Long {
        val desfases = LongArray(muestras.size) {
            muestras[it].tMonotonoNs - muestras[it].tParedMs * 1_000_000L
        }
        desfases.sort()
        return desfases[desfases.size / 2]
    }

    private fun construirCanales(
        muestras: List<MuestraInercialEntity>,
        rejilla: LongArray,
        configuracion: ConfiguracionSensores,
        pulsaciones: List<RasterizadoTactil.Pulsacion>
    ): Array<FloatArray> {
        val tiempos = LongArray(muestras.size) { muestras[it].tMonotonoNs }
        val canales = ArrayList<FloatArray>(configuracion.canalesInerciales + RasterizadoTactil.CANALES)

        fun añadir(extractor: (MuestraInercialEntity) -> Float) {
            canales.add(interpolar(tiempos, FloatArray(muestras.size) { extractor(muestras[it]) }, rejilla))
        }

        // El orden de canales es contrato con el modelo: acc, gyro, y después
        // magnetómetro o táctil. Coincide con `channel_order` del manifiesto y
        // con `ORDEN_CANALES` del cuadernillo de pre-entrenamiento.
        añadir { it.accX }; añadir { it.accY }; añadir { it.accZ }
        if (configuracion.canalesInerciales >= 6) {
            añadir { it.gyrX }; añadir { it.gyrY }; añadir { it.gyrZ }
        }
        if (configuracion.canalesInerciales >= 9) {
            añadir { it.magX ?: 0f }; añadir { it.magY ?: 0f }; añadir { it.magZ ?: 0f }
        }
        if (configuracion.incluyeTactil) {
            // Los tres canales de contacto van DETRÁS de los inerciales, en el
            // mismo orden que `ORDEN_CANALES["acc_gyro_touch"]` del cuadernillo
            // de pre-entrenamiento. El orden es contrato con el encoder.
            canales.addAll(RasterizadoTactil.rasterizar(rejilla, pulsaciones))
        }
        return canales.toTypedArray()
    }

    /** Interpolación lineal de `(tiempos, valores)` sobre `rejilla`. */
    private fun interpolar(
        tiempos: LongArray,
        valores: FloatArray,
        rejilla: LongArray
    ): FloatArray {
        val salida = FloatArray(rejilla.size)
        var j = 0
        for (i in rejilla.indices) {
            val t = rejilla[i]
            while (j < tiempos.size - 2 && tiempos[j + 1] < t) j++
            val t0 = tiempos[j]
            val t1 = tiempos[j + 1]
            salida[i] = if (t1 <= t0) {
                valores[j]
            } else {
                val f = ((t - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0)
                (valores[j] + f * (valores[j + 1] - valores[j])).toFloat()
            }
        }
        return salida
    }

    companion object {
        private const val TAG = "SegmentadorControlado"

        /** Los del manifiesto. No se tocan sin regenerar los artefactos. */
        const val TAMANO_VENTANA = 128
        const val PASO = 96
        const val HZ = 50
    }
}
