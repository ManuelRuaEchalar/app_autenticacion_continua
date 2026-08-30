package com.example.autenticacioncontinua

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.monitoring.FuenteEnergiaAndroid
import com.example.autenticacioncontinua.monitoring.FuenteMemoriaAndroid
import com.example.autenticacioncontinua.monitoring.MetodoConsumo
import com.example.autenticacioncontinua.monitoring.MonitorBloque
import com.example.autenticacioncontinua.monitoring.MotivoInvalidez
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * CARACTERIZACION DE LOS INSTRUMENTOS EN UN TERMINAL REAL.
 *
 * Esta prueba no valida codigo: valida el APARATO. Hay que ejecutarla en los DOS
 * terminales del estudio ANTES de recoger nada, y anotar su salida.
 *
 * LO QUE YA MIDIO, en el Redmi 23129RA5FL (ec56958) el 24/08:
 *
 *   - el contador de carga EXISTE pero NO RESUELVE: avanza en escalones de
 *     49 370 uAh, que es el 1% de su bateria. En 60 s de muestreo cambio una
 *     sola vez. Para juntar veinte escalones harian falta ~890 mAh, o sea unas
 *     ocho horas de bloque. El fabricante lo deriva del porcentaje;
 *   - la corriente instantanea SI resuelve: ~118 mA de media bajo carga
 *     computacional, con lecturas utiles cada 200 ms;
 *   - el PSS del proceso salio 100 / 107 / 233 MB (min/medio/max), el orden
 *     correcto. El monitor viejo daba 2 868-4 435 MB, que era el dispositivo
 *     entero;
 *   - `BatteryManager.isCharging` devolvio FALSE con el cable puesto, porque
 *     MIUI corta la carga al 80% por proteccion de bateria. Toda esa primera
 *     caracterizacion se ejecuto enchufada creyendo que no lo estaba, y sus
 *     cifras de consumo eran las del cargador. De ahi que ahora se pregunte por
 *     `EXTRA_PLUGGED` y que esta prueba FALLE si detecta alimentacion externa
 *     en el bloque sostenido, en vez de dejarlo pasar con un aviso.
 *
 * SE EJECUTA SIN CABLE, POR WIFI:
 *   adb -s <serie> tcpip 5555
 *   adb connect <ip>:5555          (y ya se puede desenchufar)
 *   adb -s <ip>:5555 shell am instrument -w \
 *     -e class com.example.autenticacioncontinua.CaracterizacionRecursosTest \
 *     com.example.autenticacioncontinua.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Con el cable puesto las lecturas de energia son las del cargador y no valen.
 */
@RunWith(AndroidJUnit4::class)
class CaracterizacionRecursosTest {

    private val energia by lazy {
        FuenteEnergiaAndroid(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    private val memoria by lazy { FuenteMemoriaAndroid() }

    /**
     * Con alimentacion externa NO se puede medir consumo. Se comprueba primero
     * y aparte, para que el motivo del fallo sea legible: si esta enchufado, lo
     * que hay que hacer es desenchufar, no depurar.
     */
    @Test
    fun aElTerminalNoDebeEstarEnchufado() {
        val enchufado = energia.estaCargando()
        Log.i(TAG, "=== ALIMENTACION ===")
        Log.i(TAG, "  hay cable / cargador: $enchufado")
        Log.i(TAG, "  porcentaje          : ${energia.porcentaje()}")
        assertTrue(
            "el terminal esta enchufado: las lecturas de energia serian las del " +
                "cargador. Conecta adb por wifi (adb tcpip 5555 + adb connect) y " +
                "desenchufa el cable antes de caracterizar.",
            !enchufado
        )
    }

    /**
     * Que resolucion tiene el contador de carga, y cada cuanto se mueve.
     *
     * De aqui sale si el contador sirve o no en este terminal. Si no sirve, el
     * consumo se mide integrando corriente, y eso HAY QUE SABERLO ANTES de
     * recoger: dos terminales que midan por metodos distintos no producen
     * cifras comparables entre si.
     */
    @Test
    fun caracterizaContadorDeCarga() {
        val carga0 = energia.cargaMicroAh()
        Log.i(TAG, "=== CONTADOR DE CARGA ===")
        Log.i(TAG, "  disponible: ${carga0 != null}  valor inicial: $carga0 uAh")
        Log.i(TAG, "  porcentaje: ${energia.porcentaje()}")
        Log.i(TAG, "  corriente : ${energia.corrienteMicroA()} uA")
        Log.i(TAG, "  enchufado : ${energia.estaCargando()}")

        if (carga0 == null) {
            Log.w(TAG, "  ESTE TERMINAL NO EXPONE EL CONTADOR DE CARGA.")
            Log.w(TAG, "  El consumo se medira integrando corriente. Documentarlo.")
            return
        }

        val lecturas = mutableListOf<Pair<Long, Long>>()   // t, carga
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < DURACION_SONDEO_MS) {
            energia.cargaMicroAh()?.let { lecturas += (System.currentTimeMillis() - t0) to it }
            Thread.sleep(PERIODO_SONDEO_MS)
        }

        val cambios = lecturas.zipWithNext().filter { (a, b) -> a.second != b.second }
        val escalones = cambios.map { (a, b) -> abs(b.second - a.second) }
        val intervalos = cambios.map { (a, b) -> b.first - a.first }

        Log.i(TAG, "  lecturas tomadas   : ${lecturas.size} en ${DURACION_SONDEO_MS / 1000} s")
        Log.i(TAG, "  veces que cambio   : ${cambios.size}")
        if (escalones.isNotEmpty()) {
            val escalonMediano = escalones.sorted()[escalones.size / 2]
            Log.i(TAG, "  escalon minimo     : ${escalones.min()} uAh")
            Log.i(TAG, "  escalon mediano    : $escalonMediano uAh")
            Log.i(TAG, "  intervalo mediano  : ${intervalos.sorted()[intervalos.size / 2]} ms")
            // Un escalon comparable al 1% de una bateria de movil (~40-50 mAh)
            // significa que el contador viene del porcentaje y no de un
            // culombimetro: no sirve para bloques de minutos.
            if (escalonMediano > UMBRAL_ESCALON_INUTIL_MICRO_AH) {
                Log.w(TAG, "  -> EL CONTADOR NO SIRVE en este terminal: su escalon es")
                Log.w(TAG, "     comparable al 1% de la bateria. Consumo por CORRIENTE.")
            } else {
                val minimoMs = 20L * intervalos.sorted()[intervalos.size / 2]
                Log.i(TAG, "  -> DURACION MINIMA DE BLOQUE: ${minimoMs / 1000} s")
                Log.i(TAG, "     (20 escalones de senal)")
            }
        } else {
            Log.w(TAG, "  EL CONTADOR NO SE MOVIO en ${DURACION_SONDEO_MS / 1000} s.")
            Log.w(TAG, "  Consumo por CORRIENTE; el contador no sirve aqui.")
        }

        assertTrue("el contador deberia dar lecturas positivas", carga0 > 0)
    }

    /**
     * La corriente instantanea tiene que EXISTIR y DISCRIMINAR.
     *
     * No basta con que devuelva un numero: si diera lo mismo en reposo que bajo
     * carga, no mediria nada y el estudio se quedaria sin variable dependiente
     * de energia en este terminal. Se compara un bloque ocupado contra uno
     * ocioso y se exige que el ocupado consuma mas.
     */
    @Test
    fun laCorrienteDiscriminaTrabajoDeReposo() {
        val enReposo = bloque("reposo") { Thread.sleep(200) }
        val conCarga = bloque("carga") {
            val v = DoubleArray(50_000) { it * 1.000001 }
            acumulador += v.sum()
        }

        Log.i(TAG, "=== LA CORRIENTE DISCRIMINA? ===")
        for (r in listOf(enReposo, conCarga)) {
            Log.i(TAG, "  ${r.etiqueta}: metodo=${r.metodoConsumo} " +
                "tasa=${r.tasaConsumoMicroAhPorHora?.toLong()} uAh/h " +
                "corriente_media=${r.corrienteMediaMicroA?.toLong()} uA " +
                "PSS=${r.pssMedioKb.toInt() / 1024} MB")
        }
        val reposo = enReposo.tasaConsumoMicroAhPorHora
        val carga = conCarga.tasaConsumoMicroAhPorHora
        Log.i(TAG, "  (acumulador $acumulador, para que no se optimice el bucle)")

        assertTrue("sin cifra de consumo en reposo", reposo != null)
        assertTrue("sin cifra de consumo bajo carga", carga != null)
        Log.i(TAG, "  NETO trabajo - reposo: ${(carga!! - reposo!!).toLong()} uAh/h")
        assertTrue(
            "el instrumento no discrimina: reposo=$reposo, carga=$carga uAh/h. " +
                "Si el terminal devuelve una corriente constante, en el no se " +
                "puede medir consumo y hay que documentarlo.",
            carga > reposo
        )
    }

    /**
     * La RAM medida debe ser la del PROCESO, no la del dispositivo.
     *
     * Es la comprobacion que habria detectado el fallo anterior: el monitor
     * viejo devolvia entre 2 868 y 4 435 MB, que es memoria de todo el telefono.
     */
    @Test
    fun laMemoriaMedidaEsLaDelProcesoYNoLaDelDispositivo() {
        val pssMb = memoria.pssProcesoKb() / 1024.0
        Log.i(TAG, "=== MEMORIA ===")
        Log.i(TAG, "  PSS del proceso: %.1f MB".format(pssMb))

        assertTrue(
            "PSS = %.1f MB: fuera del orden esperado para el proceso. ".format(pssMb) +
                "Si supera el millar, se esta midiendo el dispositivo entero, " +
                "que es exactamente el fallo corregido el 23/08.",
            pssMb in 10.0..1500.0
        )
    }

    /** Un bloque sostenido produce un resumen coherente y utilizable. */
    @Test
    fun unBloqueSostenidoProduceUnResumenValido() {
        val r = bloque("caracterizacion") {
            val v = DoubleArray(50_000) { it * 1.000001 }
            acumulador += v.sum()
        }

        Log.i(TAG, "=== BLOQUE SOSTENIDO (${DURACION_BLOQUE_MS / 1000} s) ===")
        Log.i(TAG, "  muestras          : ${r.nMuestras}")
        Log.i(TAG, "  duracion          : ${r.duracionMs} ms")
        Log.i(TAG, "  metodo            : ${r.metodoConsumo}")
        Log.i(TAG, "  consumo (contador): ${r.consumoMicroAh} uAh")
        Log.i(TAG, "  consumo (integral): ${r.consumoIntegradoMicroAh} uAh")
        Log.i(TAG, "  tasa reportable   : ${r.tasaConsumoMicroAhPorHora} uAh/h")
        Log.i(TAG, "  corriente media   : ${r.corrienteMediaMicroA} uA")
        Log.i(TAG, "  PSS min/med/max   : ${r.pssMinKb / 1024} / " +
            "${(r.pssMedioKb / 1024).toInt()} / ${r.pssMaxKb / 1024} MB")
        Log.i(TAG, "  valida            : ${r.esValida}  notas: ${r.invalidez}")
        Log.i(TAG, "  (acumulador $acumulador)")

        assertTrue("deberia haber varias muestras", r.nMuestras >= 3)
        assertTrue("la duracion deberia acercarse a la pedida",
            r.duracionMs >= DURACION_BLOQUE_MS - 1_000)
        assertEquals("con el cable fuera no deberia marcarse CARGANDO",
            false, MotivoInvalidez.CARGANDO in r.invalidez)
        assertTrue("el bloque deberia dar una cifra de consumo por algun metodo",
            r.metodoConsumo != MetodoConsumo.NINGUNO)
        assertTrue("el bloque deberia ser utilizable", r.esValida)
    }

    // ------------------------------------------------------------------

    private var acumulador = 0.0

    private fun bloque(etiqueta: String, trabajo: () -> Unit) =
        MonitorBloque(energia, memoria, periodoMuestreoMs = 250).let { monitor ->
            monitor.iniciar(etiqueta)
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < DURACION_BLOQUE_MS) trabajo()
            monitor.detener(etiqueta)!!
        }

    private companion object {
        const val TAG = "CaracterizacionRecursos"
        const val DURACION_SONDEO_MS = 60_000L
        const val PERIODO_SONDEO_MS = 200L
        const val DURACION_BLOQUE_MS = 20_000L

        /**
         * 20 000 uAh. Por encima de este escalon, el contador esta claramente
         * cuantizado al porcentaje (el 1% de una bateria de movil ronda los
         * 40 000-50 000 uAh) y no sirve para bloques de minutos.
         */
        const val UMBRAL_ESCALON_INUTIL_MICRO_AH = 20_000L
    }
}
