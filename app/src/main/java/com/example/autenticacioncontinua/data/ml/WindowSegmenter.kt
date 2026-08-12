package com.example.autenticacioncontinua.data.ml

import android.util.Log
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.ml.data.FeatureScaler
import com.example.autenticacioncontinua.ml.model.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Convierte el histórico de sensores en ventanas normalizadas, reproduciendo
 * el pipeline de `load_hmog` / `_resample_session` de `mejor.py`.
 *
 * Tres cosas que la versión anterior hacía de otra manera y que rompían la
 * compatibilidad con los pesos entrenados en la simulación:
 *
 *  1. **Orden de canales.** `mejor.py` apila acelerómetro primero
 *     (`[acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z]`, mejor.py:265-268).
 *     La versión previa emitía el giroscopio primero. Con seis canales
 *     intercambiados por parejas el modelo no falla: devuelve basura.
 *  2. **Remuestreo real.** El cuadernillo proyecta cada sesión sobre una
 *     rejilla uniforme de 50 Hz por interpolación lineal. Emparejar lecturas
 *     por cercanía de marca temporal (±10 ms) deja huecos y duplicados cuando
 *     Android entrega los eventos a ritmo irregular, que es lo normal.
 *  3. **Paso de ventana.** 96, no 64. Cambia cuántas ventanas se obtienen y
 *     cuánto se solapan, y por tanto el volumen efectivo de entrenamiento.
 *
 * Además se segmenta en SESIONES de uso continuo, cortando allí donde hay un
 * hueco largo en la señal. La sesión es la unidad de la partición
 * train/val/test: repartir ventanas solapadas entre particiones sería fuga
 * directa, porque dos ventanas contiguas comparten 32 muestras de señal.
 */
class WindowSegmenter(
    private val accelerometerRepository: IAccelerometerRepository,
    private val gyroscopeRepository: IGyroscopeRepository,
    private val manifest: ModelManifest,
    private val scaler: FeatureScaler,
    private val historyWindowMs: Long = DEFAULT_HISTORY_MS,
    private val sessionGapMs: Long = DEFAULT_SESSION_GAP_MS,
    private val maxWindows: Int = DEFAULT_MAX_WINDOWS,
    private val targetSessions: Int = DEFAULT_TARGET_SESSIONS,
    private val minWindowsPerSession: Int = DEFAULT_MIN_WINDOWS_PER_SESSION
) : IWindowSegmenter {

    private val windowSize = manifest.windowSize
    private val step = manifest.windowStep
    private val nFeatures = manifest.nFeatures
    private val targetHz = manifest.targetHz

    init {
        // Este segmentador sólo sabe producir acelerómetro + giroscopio. Una
        // configuración de sensores distinta (p. ej. con gestos táctiles)
        // necesita otra implementación, no un ajuste de constantes.
        require(nFeatures == IMU_CHANNELS) {
            "El manifiesto declara $nFeatures canales; este segmentador produce " +
                "exactamente $IMU_CHANNELS (${manifest.channelOrder})"
        }
    }

    override suspend fun getWindows(): List<SensorWindow> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - historyWindowMs
        val accel = accelerometerRepository.getAccelerometerDataSince(since)
        val gyro = gyroscopeRepository.getGyroscopeDataSince(since)

        if (accel.isEmpty() || gyro.isEmpty()) {
            Log.i(TAG, "Sin datos suficientes: accel=${accel.size}, gyro=${gyro.size}")
            return@withContext emptyList()
        }

        val accTimes = LongArray(accel.size) { accel[it].timestamp }
        val accX = FloatArray(accel.size) { accel[it].x }
        val accY = FloatArray(accel.size) { accel[it].y }
        val accZ = FloatArray(accel.size) { accel[it].z }

        val gyroTimes = LongArray(gyro.size) { gyro[it].timestamp }
        val gyroX = FloatArray(gyro.size) { gyro[it].x }
        val gyroY = FloatArray(gyro.size) { gyro[it].y }
        val gyroZ = FloatArray(gyro.size) { gyro[it].z }

        val windows = ArrayList<SensorWindow>()
        var sessionId = 0

        for ((accStart, accEnd) in detectSessions(accTimes)) {
            // Sólo el tramo cubierto por AMBOS sensores es utilizable:
            // extrapolar fuera de él inventaría señal.
            val tStart = maxOf(accTimes[accStart], gyroTimes.first())
            val tEnd = minOf(accTimes[accEnd], gyroTimes.last())
            if (tEnd <= tStart) continue

            val durationSec = (tEnd - tStart) / 1000.0
            val nSamples = (durationSec * targetHz).toInt()
            if (nSamples < windowSize) continue

            val grid = DoubleArray(nSamples) {
                tStart + (tEnd - tStart).toDouble() * it / (nSamples - 1).coerceAtLeast(1)
            }
            val channels = arrayOf(
                interpolate(accTimes, accX, grid),
                interpolate(accTimes, accY, grid),
                interpolate(accTimes, accZ, grid),
                interpolate(gyroTimes, gyroX, grid),
                interpolate(gyroTimes, gyroY, grid),
                interpolate(gyroTimes, gyroZ, grid)
            )

            var start = 0
            while (start + windowSize <= nSamples) {
                val values = FloatArray(windowSize * nFeatures)
                var k = 0
                for (t in start until start + windowSize) {
                    for (c in 0 until nFeatures) {
                        values[k++] = channels[c][t]
                    }
                }
                scaler.transformInPlace(values)
                windows.add(
                    SensorWindow(
                        values = values,
                        sessionId = sessionId,
                        startTimestampMs = grid[start].toLong()
                    )
                )
                start += step
            }
            sessionId++
        }

        Log.i(TAG, "${windows.size} ventanas de $sessionId sesiones de uso continuo")
        capWindows(windows)
    }

    /**
     * Corta el histórico en sesiones allí donde hay un hueco mayor que
     * [sessionGapMs].
     *
     * La recolección se detiene con la pantalla apagada y al agotar el límite
     * diario, así que el histórico es intrínsecamente discontinuo. Interpolar
     * a través de esos huecos fabricaría señal que nunca existió.
     *
     * @return pares (índice inicial, índice final) inclusivos.
     */
    private fun detectSessions(times: LongArray): List<Pair<Int, Int>> {
        val sessions = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (i in 1 until times.size) {
            if (times[i] - times[i - 1] > sessionGapMs) {
                if (i - 1 > start) sessions.add(start to i - 1)
                start = i
            }
        }
        if (times.size - 1 > start) sessions.add(start to times.size - 1)
        return sessions
    }

    /**
     * Interpolación lineal sobre una rejilla monótona, equivalente a
     * `np.interp`. Avanza un único puntero sobre la fuente, así que el coste
     * es lineal en el total de muestras.
     */
    private fun interpolate(
        sourceTimes: LongArray,
        sourceValues: FloatArray,
        grid: DoubleArray
    ): FloatArray {
        val out = FloatArray(grid.size)
        // Con menos de dos muestras no hay intervalo sobre el que interpolar;
        // el acceso a `sourceTimes[j + 1]` desbordaría.
        if (sourceTimes.size < 2) {
            val constant = sourceValues.firstOrNull() ?: 0f
            out.fill(constant)
            return out
        }
        var j = 0
        for (i in grid.indices) {
            val t = grid[i]
            while (j < sourceTimes.size - 2 && sourceTimes[j + 1] < t) j++

            val t0 = sourceTimes[j].toDouble()
            val t1 = sourceTimes[j + 1].toDouble()
            out[i] = when {
                t <= t0 -> sourceValues[j]
                t >= t1 -> sourceValues[j + 1]
                else -> {
                    val alpha = (t - t0) / (t1 - t0)
                    (sourceValues[j] + alpha * (sourceValues[j + 1] - sourceValues[j])).toFloat()
                }
            }
        }
        return out
    }

    /**
     * Reparte el tope de `maxWindows` ENTRE SESIONES en lugar de quedarse con
     * el tramo final del histórico.
     *
     * La versión anterior hacía `takeLast(maxWindows)`, razonándolo por la
     * deriva del comportamiento: el material viejo describe peor a quien usa
     * el teléfono hoy. El razonamiento es correcto, pero el efecto medido fue
     * el contrario del buscado. A ~468 ventanas por día de recolección, un
     * tope de 800 conserva sólo el último día y medio, así que
     * `DatasetSplitter` recibía UNA sesión, caía a su rama degradada de corte
     * temporal, y train/val/test acababan siendo tramos contiguos de la misma
     * grabación —con 32 muestras solapadas entre ventanas vecinas—. El
     * val_auc de 0.99 que salía de ahí no medía biometría, medía fuga.
     *
     * El reparto conserva la preferencia por lo reciente (se eligen las
     * `targetSessions` sesiones más nuevas) pero garantiza diversidad: unas 20
     * sesiones es el orden de magnitud de las 24 por sujeto que usa `mejor.py`,
     * y es lo que permite que la partición por sesión sea honesta.
     */
    private fun capWindows(windows: List<SensorWindow>): List<SensorWindow> {
        if (windows.isEmpty()) return windows

        val bySession = windows.groupBy { it.sessionId }

        // Una sesión de dos ventanas no describe un contexto de uso y sí
        // fragmenta la partición: `DatasetSplitter` reparte sesiones ENTERAS,
        // así que cada una tiene que valer como unidad por sí sola.
        val usable = bySession.filterValues { it.size >= minWindowsPerSession }
        if (usable.isEmpty()) {
            Log.w(
                TAG,
                "Ninguna de las ${bySession.size} sesiones llega a " +
                    "$minWindowsPerSession ventanas; no hay material utilizable"
            )
            return emptyList()
        }

        // `sessionId` crece con el tiempo, así que ordenar descendente es
        // quedarse con las sesiones más recientes.
        val elegidas = usable.keys.sortedDescending().take(targetSessions)

        // Reparto por saciado: recorriendo de la sesión más corta a la más
        // larga, cada una toma como mucho su parte alícuota del presupuesto
        // restante. Lo que las cortas no llegan a gastar se redistribuye entre
        // las largas, de modo que el tope se agota sin que una sola sesión lo
        // acapare.
        val cupos = HashMap<Int, Int>()
        var presupuesto = maxWindows
        var huecos = elegidas.size
        for (id in elegidas.sortedBy { usable.getValue(it).size }) {
            val alicuota = if (huecos > 0) presupuesto / huecos else 0
            val toma = minOf(usable.getValue(id).size, alicuota)
            cupos[id] = toma
            presupuesto -= toma
            huecos--
        }

        // Dentro de cada sesión se toman las ventanas más recientes de forma
        // CONTIGUA, no salteadas: la evaluación por fusión de N ventanas
        // consecutivas necesita que sigan siendo consecutivas en el tiempo.
        val result = ArrayList<SensorWindow>()
        for (id in elegidas.sorted()) {
            val toma = cupos[id] ?: 0
            if (toma > 0) result.addAll(usable.getValue(id).takeLast(toma))
        }

        val nSesiones = result.map { it.sessionId }.distinct().size
        Log.i(
            TAG,
            "Recorte estratificado: ${result.size} ventanas de $nSesiones " +
                "sesiones (tope $maxWindows, de ${bySession.size} sesiones " +
                "en el histórico)"
        )
        if (nSesiones < MIN_SESSIONS_FOR_HONEST_SPLIT) {
            // Se avisa en vez de fallar: con pocos días de recolección esto es
            // lo normal al empezar. Pero el número que salga de aquí NO es
            // comparable con el de una partición por sesión de verdad, y eso
            // tiene que constar en el log y no descubrirse al escribir la
            // memoria.
            Log.w(
                TAG,
                "Sólo $nSesiones sesión(es): DatasetSplitter cortará por tiempo " +
                    "dentro de la misma grabación y train/val/test quedarán " +
                    "contiguos. Las métricas de esta sesión estarán INFLADAS " +
                    "por fuga; hacen falta $MIN_SESSIONS_FOR_HONEST_SPLIT o más."
            )
        }
        return result
    }

    companion object {
        private const val TAG = "WindowSegmenter"

        /** acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z */
        private const val IMU_CHANNELS = 6

        /** Histórico considerado para entrenar: 14 días. */
        const val DEFAULT_HISTORY_MS = 14L * 24 * 60 * 60 * 1000

        /** Hueco a partir del cual se considera que empieza otra sesión. */
        const val DEFAULT_SESSION_GAP_MS = 30_000L

        /** `max_windows_per_user` de mejor.py. */
        const val DEFAULT_MAX_WINDOWS = 800

        /**
         * Sesiones entre las que se reparte el tope.
         *
         * 20 x 40 ventanas es el mismo orden que las 24 sesiones por sujeto de
         * HMOG, y deja cada sesión con material suficiente para valer como
         * unidad de partición. Subirlo mucho adelgaza cada sesión hasta que
         * deja de representar un contexto de uso.
         */
        const val DEFAULT_TARGET_SESSIONS = 20

        /** Por debajo de esto una sesión no aporta y sí fragmenta la partición. */
        const val DEFAULT_MIN_WINDOWS_PER_SESSION = 8

        /**
         * Mínimo para que `DatasetSplitter` pueda apartar sesiones enteras
         * para test y validación en vez de cortar por tiempo.
         */
        const val MIN_SESSIONS_FOR_HONEST_SPLIT = 3
    }
}
