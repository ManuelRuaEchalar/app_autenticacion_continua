package com.example.autenticacioncontinua

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.device.sensor.AccelerometerSensorImpl
import com.example.autenticacioncontinua.device.sensor.FuenteSensorAndroid
import com.example.autenticacioncontinua.domain.sensor.MuestraSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LA CAPA DE SENSORES CONTRA EL HARDWARE DE VERDAD.
 *
 * Lo que se comprueba aqui no se puede comprobar en la JVM: que el terminal
 * entregue de verdad la tasa que se le pide, que su reloj sea monotono y que no
 * haya huecos. Android trata la tasa de muestreo como una SUGERENCIA —puede
 * entregar mas lento porque el sensor no da mas, o mas rapido porque otra app
 * pidio mas y el flujo es compartido— asi que la tasa efectiva hay que MEDIRLA,
 * no suponerla. Si este terminal no llega a 50 Hz, el protocolo tiene que
 * saberlo antes de recoger sesenta dias.
 *
 * Se ejecuta con:
 *   adb -s <serie> shell am instrument -w \
 *     -e class com.example.autenticacioncontinua.CapturaSensoresTest \
 *     com.example.autenticacioncontinua.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CapturaSensoresTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fuente(tipo: TipoSensor, hz: Int) = FuenteSensorAndroid(ctx, tipo, hz)

    /** Recoge durante [ms] y devuelve lo entregado. */
    private fun recoger(f: FuenteSensorAndroid, ms: Long): List<MuestraSensor> = runBlocking {
        val recogidas = mutableListOf<MuestraSensor>()
        val trabajo = launch(Dispatchers.Default) {
            f.flujo().collect { recogidas += it }
        }
        delay(50)                      // que el colector este listo antes de arrancar
        f.iniciar()
        delay(ms)
        f.detener()
        delay(200)                     // que lleguen las ultimas en vuelo
        trabajo.cancel()
        recogidas.toList()
    }

    private fun informar(nombre: String, muestras: List<MuestraSensor>, ms: Long): Double {
        if (muestras.size < 2) {
            Log.w(TAG, "$nombre: solo ${muestras.size} muestras")
            return 0.0
        }
        val span = muestras.last().tMonotonoNs - muestras.first().tMonotonoNs
        val hz = (muestras.size - 1) * 1e9 / span
        val huecos = muestras.zipWithNext().map { (a, b) -> (b.tMonotonoNs - a.tMonotonoNs) / 1e6 }
        val ordenados = huecos.sorted()
        Log.i(TAG, "=== $nombre (${ms / 1000} s) ===")
        Log.i(TAG, "  muestras        : ${muestras.size}")
        Log.i(TAG, "  tasa efectiva   : %.2f Hz".format(hz))
        Log.i(TAG, "  intervalo mediano: %.2f ms".format(ordenados[ordenados.size / 2]))
        Log.i(TAG, "  intervalo p95   : %.2f ms".format(ordenados[(ordenados.size * 95) / 100]))
        Log.i(TAG, "  intervalo maximo: %.2f ms".format(ordenados.last()))
        Log.i(TAG, "  precision       : ${muestras.last().precision}")
        return hz
    }

    /**
     * Los tres sensores a 50 Hz durante un minuto.
     *
     * ERA 100 HASTA EL 06/09. Se bajo porque el magnetometro del terminal B
     * topa en 50 Hz —medido: 20.00 ms clavados de intervalo— y el diseño
     * cruzado persona x dispositivo exige la misma tasa en los dos aparatos.
     *
     * Un minuto y no diez segundos porque los huecos que importan —una
     * recoleccion de basura, el gobernador bajando frecuencia, otra app
     * despertando— no aparecen en diez segundos.
     */
    @Test
    fun losTresSensoresEntreganLaTasaPedida() {
        for (tipo in TipoSensor.entries) {
            val f = fuente(tipo, HZ)
            if (!f.disponible) {
                Log.w(TAG, "${tipo.clave}: EL TERMINAL NO LO TIENE. Queda documentado.")
                continue
            }
            val muestras = recoger(f, DURACION_MS)
            val hz = informar(tipo.clave, muestras, DURACION_MS)

            assertTrue("${tipo.clave}: no entrego ninguna muestra", muestras.isNotEmpty())
            assertTrue(
                "${tipo.clave}: tasa efectiva %.1f Hz, muy lejos de los $HZ pedidos. ".format(hz) +
                    "Si el terminal no llega, el protocolo tiene que bajar la tasa " +
                    "objetivo ANTES de recoger, no despues.",
                hz >= HZ * TOLERANCIA_BAJA && hz <= HZ * TOLERANCIA_ALTA
            )
        }
    }

    /**
     * El reloj de las muestras tiene que ser ESTRICTAMENTE creciente.
     *
     * Es la condicion que hace utilizable `tMonotonoNs` para calcular
     * intervalos. Si retrocediera, la tasa efectiva y los huecos saldrian
     * negativos y nadie lo miraria hasta el analisis.
     */
    @Test
    fun elRelojDeLasMuestrasNoRetrocede() {
        val f = fuente(TipoSensor.ACELEROMETRO, HZ)
        val muestras = recoger(f, 10_000)
        assertTrue("sin muestras", muestras.size > 100)

        var retrocesos = 0
        for ((a, b) in muestras.zipWithNext()) {
            if (b.tMonotonoNs <= a.tMonotonoNs) retrocesos++
            if (b.tParedMs < a.tParedMs) retrocesos++
        }
        Log.i(TAG, "reloj: ${muestras.size} muestras, $retrocesos retrocesos")
        assertEquals("el reloj retrocedio", 0, retrocesos)
    }

    /**
     * No debe haber huecos grandes.
     *
     * Un hueco es tiempo del participante tecleando que no quedo registrado. Se
     * tolera alguno —el sistema no es de tiempo real— pero no muchos: si el 5%
     * de los intervalos pasa de cinco periodos, la serie no es continua y el
     * analisis por ventanas de 2.56 s deja de tener sentido.
     */
    @Test
    fun noHayHuecosApreciablesEnLaSerie() {
        val f = fuente(TipoSensor.ACELEROMETRO, HZ)
        val muestras = recoger(f, DURACION_MS)
        val periodoMs = 1000.0 / HZ
        val huecos = muestras.zipWithNext()
            .map { (a, b) -> (b.tMonotonoNs - a.tMonotonoNs) / 1e6 }
        val grandes = huecos.count { it > periodoMs * 5 }
        val fraccion = grandes.toDouble() / huecos.size

        Log.i(TAG, "huecos > ${periodoMs * 5} ms: $grandes de ${huecos.size} " +
            "(%.2f%%)".format(fraccion * 100))
        Log.i(TAG, "  el mayor: %.1f ms".format(huecos.max()))

        assertTrue("%.1f%% de intervalos son huecos grandes".format(fraccion * 100),
            fraccion < 0.05)
    }

    /**
     * LA RECOGIDA AMBIENTAL NO SE ENTERA DEL REFACTOR.
     *
     * `AccelerometerSensorImpl` paso de ser una implementacion a ser un
     * adaptador sobre la fuente generica. Tiene que seguir entregando
     * `AccelerometerData` a 50 Hz, porque asi son las 1,3 millones de filas ya
     * recogidas y asi las corta `WindowSegmenter`.
     */
    @Test
    fun elAdaptadorAmbientalSigueEntregandoA50Hz() = runBlocking {
        val sensor = AccelerometerSensorImpl(ctx)
        val datos = mutableListOf<Long>()
        val trabajo = launch(Dispatchers.Default) {
            sensor.getSensorDataFlow().collect { datos += it.timestamp }
        }
        delay(50)
        sensor.startListening()
        delay(10_000)
        sensor.stopListening()
        delay(200)
        trabajo.cancel()

        assertTrue("el adaptador ambiental no entrego nada", datos.size > 100)
        val hz = (datos.size - 1) * 1000.0 / (datos.last() - datos.first())
        Log.i(TAG, "=== adaptador ambiental ===")
        Log.i(TAG, "  muestras: ${datos.size}  tasa: %.2f Hz".format(hz))
        assertTrue(
            "el adaptador ambiental entrega %.1f Hz; deberia seguir en 50".format(hz),
            hz >= AccelerometerSensorImpl.HZ_AMBIENTAL * TOLERANCIA_BAJA &&
                hz <= AccelerometerSensorImpl.HZ_AMBIENTAL * TOLERANCIA_ALTA
        )
        // Y su marca de tiempo sigue siendo la de pared, que es la que hay en la
        // tabla `accelerometer_data` y la que espera el segmentador.
        val ahora = System.currentTimeMillis()
        assertTrue("el timestamp deberia ser reloj de pared, no monotono",
            datos.last() in (ahora - 60_000)..(ahora + 1_000))
    }

    /** El magnetometro puede faltar: la app tiene que seguir, no fallar. */
    @Test
    fun sinMagnetometroLaCapturaSigueYQuedaRegistrado() = runBlocking {
        val f = fuente(TipoSensor.MAGNETOMETRO, HZ)
        Log.i(TAG, "=== magnetometro ===")
        Log.i(TAG, "  disponible: ${f.disponible}")
        if (!f.disponible) {
            // Iniciar y detener no deben lanzar: la sesion sigue siendo valida
            // con la columna del campo magnetico a nulo.
            f.iniciar()
            f.detener()
            val nada = withTimeoutOrNull(1_000) { f.flujo().toList() }
            assertTrue("un sensor ausente no debe emitir nada", nada.isNullOrEmpty())
            return@runBlocking
        }
        val muestras = recoger(f, 5_000)
        informar("magnetometro", muestras, 5_000)
        assertTrue(muestras.isNotEmpty())
    }

    private companion object {
        const val TAG = "CapturaSensores"
        const val HZ = 50
        const val DURACION_MS = 60_000L

        /**
         * Tolerancia asimetrica y ancha a proposito.
         *
         * Por abajo, un terminal que entregue por debajo del 70% de lo pedido
         * no sirve para el protocolo y hay que saberlo. Por arriba se admite un
         * exceso porque el flujo de sensores es compartido: si otra aplicacion
         * pide 200 Hz, este listener recibe a 200 Hz aunque haya pedido 100.
         */
        const val TOLERANCIA_BAJA = 0.70
        const val TOLERANCIA_ALTA = 2.50
    }
}
