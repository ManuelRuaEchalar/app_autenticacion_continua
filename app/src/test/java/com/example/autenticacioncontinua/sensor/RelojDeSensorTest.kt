package com.example.autenticacioncontinua.sensor

import com.example.autenticacioncontinua.domain.sensor.BaseDeReloj
import com.example.autenticacioncontinua.domain.sensor.RelojDeSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la alineación de relojes del sensor.
 *
 * Aquí se decide qué marca de tiempo lleva cada una de los ~22 millones de
 * muestras del estudio. Un fallo no rompe nada visible: simplemente hace que la
 * tasa efectiva salga disparatada, o que los intervalos entre muestras lleven
 * dentro los saltos de NTP, y eso no se descubre hasta analizar sesenta días de
 * recogida.
 */
class RelojDeSensorTest {

    /** Un instante plausible de reloj monótono: unas 10 h desde el arranque. */
    private val monotonoNs = 36_000_000_000_000L

    /** Un instante plausible de reloj de pared, en ms. */
    private val paredMs = 1_756_000_000_000L

    @Test
    fun `reconoce el reloj monotono, que es lo que promete la documentacion`() {
        val r = RelojDeSensor()
        r.anclar(monotonoNs, monotonoNs, paredMs)

        assertEquals(BaseDeReloj.MONOTONO, r.base)
        // Una muestra 10 ms despues conserva su propia marca.
        val t = monotonoNs + 10_000_000
        assertEquals(t, r.monotonoNs(t, monotonoNs + 10_000_000))
    }

    /**
     * Hay controladores que devuelven épocas Unix en nanosegundos. Sin
     * detectarlo, la marca de tiempo caería a 55 000 años del arranque y
     * cualquier cálculo de tasa saldría absurdo.
     */
    @Test
    fun `reconoce y corrige un reloj en epocas Unix`() {
        val r = RelojDeSensor()
        val eventoEpoca = paredMs * 1_000_000L
        r.anclar(eventoEpoca, monotonoNs, paredMs)

        assertEquals(BaseDeReloj.EPOCA, r.base)
        // 10 ms mas tarde en la base del evento -> 10 ms mas tarde en monotono.
        val t = r.monotonoNs(eventoEpoca + 10_000_000, monotonoNs + 10_000_000)
        assertEquals(monotonoNs + 10_000_000, t)
    }

    /**
     * Si no se reconoce la base, se descarta el reloj del evento.
     *
     * Perder la precisión del controlador es mucho menos grave que construir
     * los 22 millones de marcas de tiempo sobre una base que no se entiende.
     */
    @Test
    fun `ante una base desconocida usa el reloj del sistema`() {
        val r = RelojDeSensor()
        r.anclar(42L, monotonoNs, paredMs)

        assertEquals(BaseDeReloj.DESCONOCIDA, r.base)
        val ahora = monotonoNs + 10_000_000
        assertEquals(ahora, r.monotonoNs(43L, ahora))
    }

    @Test
    fun `un timestamp de cero es base desconocida y no epoca de 1970`() {
        val r = RelojDeSensor()
        r.anclar(0L, monotonoNs, paredMs)
        assertEquals(BaseDeReloj.DESCONOCIDA, r.base)
    }

    /**
     * ES LA PRUEBA QUE JUSTIFICA TODA LA CLASE. El reloj de pared se DERIVA del
     * monótono desde un ancla; si se leyera del sistema en cada muestra, un
     * salto de NTP a mitad de un bloque de cinco minutos aparecería en los datos
     * como un hueco o un solapamiento que no ocurrió.
     */
    @Test
    fun `un salto de NTP no llega a los datos`() {
        val r = RelojDeSensor()
        r.anclar(monotonoNs, monotonoNs, paredMs)

        // Un segundo de muestras, a 100 Hz, mientras el reloj de pared del
        // sistema pega un salto de 45 s hacia atras.
        val paredes = (1..100).map { i ->
            val t = monotonoNs + i * 10_000_000L
            r.paredMs(r.monotonoNs(t, t))
        }

        // Estrictamente creciente, sin un solo salto.
        for ((a, b) in paredes.zipWithNext()) {
            assertTrue("el tiempo de pared retrocedio: $a -> $b", b > a)
            assertEquals("el paso deberia ser de 10 ms", 10L, b - a)
        }
        assertEquals(paredMs + 1_000, paredes.last())
    }

    @Test
    fun `el tiempo de pared arranca exactamente en el ancla`() {
        val r = RelojDeSensor()
        r.anclar(monotonoNs, monotonoNs, paredMs)
        assertEquals(paredMs, r.paredMs(monotonoNs))
    }

    @Test
    fun `no esta anclado hasta que llega la primera muestra`() {
        val r = RelojDeSensor()
        assertEquals(false, r.estaAnclado)
        r.anclar(monotonoNs, monotonoNs, paredMs)
        assertTrue(r.estaAnclado)
    }

    /**
     * Un retraso de entrega normal —el evento se genera antes de que la
     * aplicación lo reciba— no debe cambiar la clasificación de la base.
     */
    @Test
    fun `un retraso de entrega de decimas de segundo sigue siendo monotono`() {
        val r = RelojDeSensor()
        r.anclar(monotonoNs - 300_000_000L, monotonoNs, paredMs)
        assertEquals(BaseDeReloj.MONOTONO, r.base)
    }
}
