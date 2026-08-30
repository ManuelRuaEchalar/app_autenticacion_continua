package com.example.autenticacioncontinua.textos

import com.example.autenticacioncontinua.domain.textos.Parrafo
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos.Companion.ESPANOL
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos.Companion.LATIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del reparto de idiomas y párrafos.
 *
 * Lo que se comprueba aquí decide qué texto ve cada participante en cada uno de
 * los 750 bloques del estudio. Un fallo no rompe nada visible: produce un
 * reparto plausible y sesgado, y el sesgo sólo se descubriría al analizar.
 */
class SelectorDeParrafosTest {

    private fun corpus(nEs: Int = 100, nLa: Int = 60) = SelectorDeParrafos(
        mapOf(
            ESPANOL to (1..nEs).map { Parrafo("es_%04d".format(it), ESPANOL, "texto es $it") },
            LATIN to (1..nLa).map { Parrafo("la_%04d".format(it), LATIN, "texto la $it") }
        )
    )

    // ------------------------------------------------------------------
    // Reparto de idiomas
    // ------------------------------------------------------------------

    @Test
    fun `cada sesion lleva dos bloques en espanol y uno en latin`() {
        val s = corpus()
        for (sesion in 1..10) {
            val idiomas = s.idiomasDeSesion(sesion)
            assertEquals(3, idiomas.size)
            assertEquals("sesion $sesion", 2, idiomas.count { it == ESPANOL })
            assertEquals("sesion $sesion", 1, idiomas.count { it == LATIN })
        }
    }

    /**
     * Si el latín fuera siempre el tercer bloque, cargaría con todo el
     * cansancio de los quince minutos anteriores y «efecto del idioma» sería
     * indistinguible de «efecto de la fatiga».
     */
    @Test
    fun `el latin rota de posicion entre sesiones consecutivas`() {
        val s = corpus()
        val posiciones = (1..6).map { sesion ->
            s.idiomasDeSesion(sesion).indexOf(LATIN)
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), posiciones)
    }

    /**
     * A lo largo de las diez sesiones previstas, cada posición de bloque tiene
     * que acumular latín un número parecido de veces. Es la comprobación de que
     * la rotación cumple su función y no sólo se mueve.
     */
    @Test
    fun `tras diez sesiones el latin se reparte entre las tres posiciones`() {
        val s = corpus()
        val cuenta = IntArray(3)
        for (sesion in 1..10) cuenta[s.idiomasDeSesion(sesion).indexOf(LATIN)]++

        // Con 10 sesiones y 3 posiciones el reparto exacto es imposible: lo
        // exigible es que ninguna posición se lleve más de una vez de más.
        assertTrue("reparto ${cuenta.toList()}", cuenta.max() - cuenta.min() <= 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `la sesion cero es un error de programacion`() {
        corpus().idiomaDeBloque(0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un bloque fuera de rango es un error de programacion`() {
        corpus().idiomaDeBloque(1, 3)
    }

    // ------------------------------------------------------------------
    // Selección de párrafos
    // ------------------------------------------------------------------

    @Test
    fun `entrega el numero de parrafos pedido, del idioma pedido`() {
        val p = corpus().parrafosPara(LATIN, semillaSesion = 7, indiceBloque = 0, yaVistos = emptySet())

        assertEquals(SelectorDeParrafos.PARRAFOS_POR_BLOQUE, p.size)
        assertTrue(p.all { it.idioma == LATIN })
        assertEquals("no debe repetir dentro del mismo bloque", p.size, p.map { it.id }.toSet().size)
    }

    @Test
    fun `no repite un parrafo que el participante ya vio`() {
        val s = corpus(nEs = 20)
        val vistos = (1..15).map { "es_%04d".format(it) }.toSet()

        val p = s.parrafosPara(ESPANOL, 1, 0, vistos, cuantos = 5)
        assertTrue("se colo un parrafo ya visto", p.none { it.id in vistos })
    }

    /**
     * Con diez sesiones y doce veces el texto necesario esto no debería pasar,
     * pero si pasara, repetir un párrafo es mucho mejor que dejar a alguien
     * mirando una pantalla vacía a mitad de un bloque cronometrado.
     */
    @Test
    fun `si se agotan los no vistos, reutiliza en vez de quedarse corto`() {
        val s = corpus(nEs = 10)
        val vistos = (1..8).map { "es_%04d".format(it) }.toSet()

        val p = s.parrafosPara(ESPANOL, 1, 0, vistos, cuantos = 6)
        assertEquals("debe entregar los 6 pedidos", 6, p.size)
        // Los dos no vistos tienen que estar los primeros.
        assertTrue(p.take(2).none { it.id in vistos })
    }

    @Test
    fun `quedanSinVer cuenta lo que le queda al participante`() {
        val s = corpus(nEs = 20)
        assertEquals(20, s.quedanSinVer(ESPANOL, emptySet()))
        assertEquals(17, s.quedanSinVer(ESPANOL, setOf("es_0001", "es_0002", "es_0003")))
    }

    // ------------------------------------------------------------------
    // Reproducibilidad
    // ------------------------------------------------------------------

    /**
     * Con la semilla guardada en `sesiones_controladas.semillaSeleccion` se
     * puede reconstruir meses después la secuencia exacta de textos que vio un
     * participante, sin haberla almacenado entera.
     */
    @Test
    fun `la misma semilla produce la misma secuencia`() {
        val a = corpus().parrafosPara(ESPANOL, 12345, 1, emptySet())
        val b = corpus().parrafosPara(ESPANOL, 12345, 1, emptySet())
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test
    fun `semillas distintas producen secuencias distintas`() {
        val a = corpus().parrafosPara(ESPANOL, 1, 0, emptySet())
        val b = corpus().parrafosPara(ESPANOL, 2, 0, emptySet())
        assertTrue("dos semillas dieron el mismo orden", a.map { it.id } != b.map { it.id })
    }

    /**
     * Si la semilla dependiera sólo de la sesión, los tres bloques barajarían
     * igual y el segundo empezaría por el mismo párrafo que el primero.
     */
    @Test
    fun `dos bloques de la misma sesion no barajan igual`() {
        val s = corpus()
        val b0 = s.parrafosPara(ESPANOL, 99, 0, emptySet())
        val b2 = s.parrafosPara(ESPANOL, 99, 2, emptySet())
        assertTrue("los dos bloques dieron el mismo orden", b0.map { it.id } != b2.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un corpus sin uno de los dos idiomas es un error de programacion`() {
        SelectorDeParrafos(mapOf(ESPANOL to listOf(Parrafo("es_0001", ESPANOL, "x"))))
    }
}
