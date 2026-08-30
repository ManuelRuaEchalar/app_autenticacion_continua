package com.example.autenticacioncontinua.device.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.example.autenticacioncontinua.domain.sensor.IFuenteSensor
import com.example.autenticacioncontinua.domain.sensor.MuestraSensor
import com.example.autenticacioncontinua.domain.sensor.RelojDeSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fuente genérica de muestras de un sensor de tres ejes.
 *
 * SUSTITUYE A TRES CLASES QUE HABRÍAN SIDO IGUALES. `AccelerometerSensorImpl` y
 * `GyroscopeSensorImpl` eran idénticas salvo el tipo de sensor y el tipo de
 * dato: mismo registro, mismo flujo, misma parada. Añadir el magnetómetro por
 * copia habría dejado tres sitios donde arreglar el mismo fallo. Ahora son
 * configuraciones de esta clase, y las interfaces viejas siguen existiendo como
 * adaptadores para que la recogida ambiental no se entere del cambio.
 *
 * LA TASA ES UNA SUGERENCIA. `registerListener` acepta un periodo en µs, pero
 * Android no garantiza cumplirlo: puede entregar más lento (el sensor no da
 * para más) o más rápido (otra aplicación pidió una tasa mayor y el flujo es
 * compartido). Lo que de verdad ocurrió se mide después con el reloj monótono
 * — `BloqueDao.tasaEfectivaHz` — y hay que comprobarlo, no suponerlo.
 *
 * SIN AGRUPACIÓN POR LOTES (`maxReportLatencyUs = 0`). El sensor puede acumular
 * muestras en su propio búfer y entregarlas de golpe, lo que ahorra despertares
 * del procesador y baja bastante el consumo. No se usa aquí y es una decisión
 * consciente: este proyecto MIDE el consumo, y activar la agrupación cambiaría
 * la variable dependiente sin que apareciera en ninguna tabla del diseño. Si
 * alguna vez se quiere estudiar, entra como condición del experimento y no
 * como comportamiento por omisión.
 */
class FuenteSensorAndroid(
    context: Context,
    override val tipo: TipoSensor,
    override val hzSolicitados: Int,
    /** Capacidad del búfer del flujo. Ver la nota de [_flujo]. */
    capacidadBuffer: Int = CAPACIDAD_POR_DEFECTO
) : IFuenteSensor, SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? = sensorManager.getDefaultSensor(tipo.codigoAndroid)

    override val disponible: Boolean get() = sensor != null

    private val reloj = RelojDeSensor()

    /**
     * `DROP_OLDEST` y no `SUSPEND`.
     *
     * Si el consumidor se atasca, es preferible perder las muestras más viejas
     * que bloquear el hilo del sensor: bloquearlo retrasa TODAS las entregas
     * posteriores y corrompe la cadencia de la serie entera, que es justo lo
     * que se está midiendo. Las pérdidas se ven después en la tasa efectiva.
     */
    private val _flujo = MutableSharedFlow<MuestraSensor>(
        extraBufferCapacity = capacidadBuffer,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Muestras entregadas desde el último [iniciar]. Para diagnóstico. */
    @Volatile
    var entregadas: Long = 0
        private set

    override fun iniciar() {
        val s = sensor
        if (s == null) {
            // No es un error: hay terminales sin magnetómetro y la sesión sigue
            // siendo válida con esa columna a nulo. Pero tiene que constar.
            Log.w(TAG, "el terminal no tiene ${tipo.clave}; no se capturara")
            return
        }
        entregadas = 0
        sensorManager.registerListener(this, s, periodoUs(hzSolicitados), 0)
    }

    override fun detener() {
        sensorManager.unregisterListener(this)
    }

    override fun flujo(): Flow<MuestraSensor> = _flujo.asSharedFlow()

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != tipo.codigoAndroid) return

        val monotonoAhora = SystemClock.elapsedRealtimeNanos()
        if (!reloj.estaAnclado) {
            reloj.anclar(e.timestamp, monotonoAhora, System.currentTimeMillis())
            Log.i(TAG, "${tipo.clave}: reloj del sensor en base ${reloj.base}")
        }
        val monotono = reloj.monotonoNs(e.timestamp, monotonoAhora)

        entregadas++
        _flujo.tryEmit(
            MuestraSensor(
                tipo = tipo,
                x = e.values[0],
                y = e.values[1],
                z = e.values[2],
                tParedMs = reloj.paredMs(monotono),
                tMonotonoNs = monotono,
                precision = e.accuracy
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Interesa sobre todo en el magnetometro, que pierde calibracion cerca
        // de metal o de un altavoz. No se corta la captura: se anota, y la
        // precision viaja con cada muestra para poder filtrar despues.
        if (tipo == TipoSensor.MAGNETOMETRO) {
            Log.i(TAG, "precision del magnetometro -> $accuracy")
        }
    }

    companion object {
        private const val TAG = "FuenteSensor"

        /**
         * Dos segundos de margen a 100 Hz.
         *
         * Con menos, una pausa del consumidor —una escritura a disco, una
         * recolección de basura— empieza a tirar muestras; con mucho más, un
         * consumidor atascado acumularía memoria sin que nadie se entere.
         */
        const val CAPACIDAD_POR_DEFECTO = 200

        fun periodoUs(hz: Int): Int {
            require(hz > 0) { "hz=$hz" }
            return 1_000_000 / hz
        }
    }
}
