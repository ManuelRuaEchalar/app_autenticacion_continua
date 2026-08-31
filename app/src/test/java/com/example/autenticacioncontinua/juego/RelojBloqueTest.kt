package com.example.autenticacioncontinua.juego

import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.domain.juego.RelojBloque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cronómetro del bloque.
 *
 * De aquí sale la duración que se guarda en `bloques.finMs`, y con ella
 * cualquier tasa por unidad de tiempo del estudio, empezando por las
 * pulsaciones por minuto. Un fallo aquí no rompe nada visible: produce bloques
 * con duraciones plausibles y equivocadas.
 */
class RelojBloqueTest {

    /** Reloj de mentira que sólo avanza cuando la prueba lo dice. */
    private class RelojFalso(var t: Long = 1_000L) {
        val leer: () -> Long = { t }
        fun avanzar(ms: Long) { t += ms }
    }

    // ------------------------------------------------------------------
    // Corte por tiempo
    // ------------------------------------------------------------------

    /**
     * El bloque dura un tiempo FIJO, corte donde corte el texto: si terminara al
     * acabar el párrafo, quien teclea rápido aportaría menos datos que quien
     * teclea despacio y la cantidad de señal quedaría correlacionada con la
     * persona.
     */
    @Test
    fun `el bloque termina exactamente al agotarse su duracion`() {
        val reloj = RelojFalso()
        val r = RelojBloque(BloqueEntity.DURACION_MS, reloj.leer)
        r.iniciar()

        reloj.avanzar(BloqueEntity.DURACION_MS - 1)
        assertFalse("un milisegundo antes sigue vivo", r.terminadoPorTiempo)

        reloj.avanzar(1)
        assertTrue("y al llegar justo, termina", r.terminadoPorTiempo)
        assertEquals(0L, r.restanteMs)
        assertEquals(1f, r.fraccion, 0f)
    }

    @Test
    fun `el restante y la fraccion avanzan con el reloj`() {
        val reloj = RelojFalso()
        val r = RelojBloque(100_000L, reloj.leer)
        r.iniciar()

        assertEquals(100_000L, r.restanteMs)
        assertEquals(0f, r.fraccion, 1e-6f)

        reloj.avanzar(25_000)
        assertEquals(75_000L, r.restanteMs)
        assertEquals(0.25f, r.fraccion, 1e-6f)
    }

    /** Pasado el final, el restante no se vuelve negativo ni la fracción pasa de 1. */
    @Test
    fun `no se pasa de los limites si el reloj sigue corriendo`() {
        val reloj = RelojFalso()
        val r = RelojBloque(1_000L, reloj.leer)
        r.iniciar()
        reloj.avanzar(9_999)

        assertEquals(0L, r.restanteMs)
        assertEquals(1f, r.fraccion, 0f)
    }

    @Test
    fun `sin iniciar no ha terminado ni ha consumido tiempo`() {
        val reloj = RelojFalso()
        val r = RelojBloque(1_000L, reloj.leer)
        reloj.avanzar(5_000)

        assertFalse(r.arrancado)
        assertFalse(r.terminadoPorTiempo)
        assertEquals(0L, r.transcurridoMs)
    }

    // ------------------------------------------------------------------
    // Interrupción
    // ------------------------------------------------------------------

    /**
     * LA CLAVE DE TODA LA CLASE. Una llamada entrante corta el bloque; lo que NO
     * se hace es estirar la duración hasta el valor nominal. Un bloque de 40 s
     * marcado como interrumpido es analizable; uno de 40 s presentado como de
     * 100 s corrompe en silencio cualquier tasa por unidad de tiempo.
     */
    @Test
    fun `interrumpir corta el bloque sin falsear el tiempo`() {
        val reloj = RelojFalso()
        val r = RelojBloque(100_000L, reloj.leer)
        r.iniciar()
        reloj.avanzar(40_000)

        r.interrumpir("llamada entrante")

        assertTrue(r.terminado)
        assertFalse("no termino por tiempo, lo cortaron", r.terminadoPorTiempo)
        assertTrue(r.interrumpido)
        assertEquals("llamada entrante", r.motivoInterrupcion)
        assertEquals("la duracion es la real, no la nominal", 40_000L, r.transcurridoMs)
    }

    /**
     * El tiempo se congela al interrumpir. Si siguiera corriendo, la duración
     * registrada crecería mientras la pantalla de resumen está abierta y un
     * bloque de 40 s acabaría anotado como de varios minutos.
     */
    @Test
    fun `el tiempo se congela al interrumpir`() {
        val reloj = RelojFalso()
        val r = RelojBloque(100_000L, reloj.leer)
        r.iniciar()
        reloj.avanzar(30_000)
        r.interrumpir("pantalla apagada")

        reloj.avanzar(600_000)

        assertEquals(30_000L, r.transcurridoMs)
    }

    /**
     * El `onStop` llega detrás de la llamada entrante. La segunda interrupción
     * no debe pisar el motivo real ni mover la duración.
     */
    @Test
    fun `interrumpir dos veces conserva el primer motivo y la primera duracion`() {
        val reloj = RelojFalso()
        val r = RelojBloque(100_000L, reloj.leer)
        r.iniciar()
        reloj.avanzar(10_000)
        r.interrumpir("llamada entrante")

        reloj.avanzar(5_000)
        r.interrumpir("la aplicacion paso a segundo plano")

        assertEquals("llamada entrante", r.motivoInterrupcion)
        assertEquals(10_000L, r.transcurridoMs)
    }

    /**
     * Un bloque interrumpido sin explicación es indistinguible de uno que salió
     * corto por un fallo del programa, y en el análisis los dos casos se tratan
     * distinto.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `interrumpir sin motivo no se permite`() {
        val r = RelojBloque(1_000L) { 0L }
        r.iniciar()
        r.interrumpir("   ")
    }

    @Test
    fun `iniciar de nuevo limpia una interrupcion anterior`() {
        val reloj = RelojFalso()
        val r = RelojBloque(1_000L, reloj.leer)
        r.iniciar()
        r.interrumpir("algo")

        r.iniciar()

        assertFalse(r.interrumpido)
        assertEquals("", r.motivoInterrupcion)
        assertEquals(0L, r.transcurridoMs)
    }
}
