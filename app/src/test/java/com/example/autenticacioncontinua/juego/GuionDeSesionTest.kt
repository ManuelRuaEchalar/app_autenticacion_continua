package com.example.autenticacioncontinua.juego

import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.domain.juego.FaseDeSesion
import com.example.autenticacioncontinua.domain.juego.GuionDeSesion
import com.example.autenticacioncontinua.domain.textos.Parrafo
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El orden de las fases de una visita.
 *
 * La comprobación importante es la ROTACIÓN DEL LATÍN. Si el bloque de latín
 * fuera siempre el último, cargaría con todo el cansancio de los bloques
 * anteriores y «efecto del idioma» sería indistinguible de «efecto de la
 * fatiga». Es el mismo argumento que el contrabalanceo del orden de
 * dispositivos: lo que no se contrabalancea, se confunde.
 */
class GuionDeSesionTest {

    private val selector = SelectorDeParrafos(
        mapOf(
            "es" to (1..40).map { Parrafo("es_%04d".format(it), "es", "texto es $it") },
            "la" to (1..40).map { Parrafo("la_%04d".format(it), "la", "texto la $it") }
        )
    )

    private fun bloques(sesion: Int) =
        GuionDeSesion.fases(sesion, selector).filterIsInstance<FaseDeSesion.Bloque>()

    // ------------------------------------------------------------------
    // Estructura
    // ------------------------------------------------------------------

    @Test
    fun `la visita es aclimatacion, tres bloques y fin`() {
        val fases = GuionDeSesion.fases(1, selector)

        assertEquals(5, fases.size)
        assertEquals(FaseDeSesion.Aclimatacion, fases.first())
        assertEquals(FaseDeSesion.Fin, fases.last())
        assertEquals(3, fases.filterIsInstance<FaseDeSesion.Bloque>().size)
    }

    /**
     * No hay descansos (decisión del 30/08, al recortar la visita de ~18 min a
     * ~5). Si alguien los reintroduce, esta prueba lo obliga a mirar la nota de
     * [FaseDeSesion] y a declarar el cambio en `VERSION_PROTOCOLO`.
     */
    @Test
    fun `no hay fases de descanso entre bloques`() {
        val fases = GuionDeSesion.fases(1, selector)
        val intermedias = fases.drop(1).dropLast(1)

        assertTrue(
            "entre la aclimatacion y el fin solo hay bloques",
            intermedias.all { it is FaseDeSesion.Bloque }
        )
    }

    @Test
    fun `los bloques van numerados 0, 1, 2`() {
        assertEquals(listOf(0, 1, 2), bloques(1).map { it.indice })
    }

    /** Aclimatación de 10 s más tres bloques: poco más de cinco minutos. */
    @Test
    fun `la visita dura lo que se acordo`() {
        val total = GuionDeSesion.duracionTotalMs(1, selector)

        assertEquals(
            FaseDeSesion.ACLIMATACION_MS + 3 * BloqueEntity.DURACION_MS,
            total
        )
        assertTrue("no llega a seis minutos", total < 6 * 60_000L)
    }

    // ------------------------------------------------------------------
    // Idiomas
    // ------------------------------------------------------------------

    @Test
    fun `cada sesion lleva dos bloques en espanol y uno en latin`() {
        for (sesion in 1..10) {
            val idiomas = bloques(sesion).map { it.idioma }
            assertEquals("sesion $sesion", 2, idiomas.count { it == "es" })
            assertEquals("sesion $sesion", 1, idiomas.count { it == "la" })
        }
    }

    @Test
    fun `la posicion del latin rota 0, 1, 2 y vuelve a empezar`() {
        val posiciones = (1..7).map { sesion ->
            bloques(sesion).first { it.idioma == "la" }.indice
        }

        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), posiciones)
    }

    /**
     * A lo largo de las diez sesiones del protocolo, cada posición de bloque
     * acumula latín y español de forma equilibrada. Es la razón de ser de la
     * rotación: sin ella, una posición concentraría todo el latín y toda la
     * fatiga que le corresponde.
     */
    @Test
    fun `en nueve sesiones cada posicion recibe el latin el mismo numero de veces`() {
        val porPosicion = (1..9)
            .map { bloques(it).first { b -> b.idioma == "la" }.indice }
            .groupingBy { it }.eachCount()

        assertEquals(mapOf(0 to 3, 1 to 3, 2 to 3), porPosicion)
    }

    // ------------------------------------------------------------------
    // Bordes
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `la sesion cero no existe`() {
        GuionDeSesion.fases(0, selector)
    }
}
