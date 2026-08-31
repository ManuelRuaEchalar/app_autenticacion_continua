package com.example.autenticacioncontinua.juego

import com.example.autenticacioncontinua.domain.juego.ListaDeVerificacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista que bloquea el inicio de una visita.
 *
 * Lo que se comprueba aquí no es cosmético: de la primera tanda de agosto
 * salieron dos teléfonos con CERO datos por saltarse pasos de configuración, y
 * no se detectó hasta días después porque nada avisaba.
 */
class ListaDeVerificacionTest {

    private fun lista(
        bateria: Float? = 80f,
        etiqueta: Boolean = true,
        participante: Boolean = true
    ) = ListaDeVerificacion.para(bateria, etiqueta, participante)

    private val manuales = setOf(ListaDeVerificacion.BRILLO, ListaDeVerificacion.NO_MOLESTAR)

    @Test
    fun `con todo en orden y las manuales marcadas se puede empezar`() {
        assertTrue(ListaDeVerificacion.puedeEmpezar(lista(), manuales))
    }

    @Test
    fun `sin marcar las manuales no se puede empezar`() {
        assertFalse(ListaDeVerificacion.puedeEmpezar(lista(), emptySet()))
        assertEquals(2, ListaDeVerificacion.pendientes(lista(), emptySet()).size)
    }

    // ------------------------------------------------------------------
    // Las automaticas
    // ------------------------------------------------------------------

    /**
     * LA REGLA QUE HACE QUE LA LISTA SIRVA DE ALGO. Si una comprobacion
     * automatica se pudiera saltar marcando una casilla, la lista dejaria de ser
     * una barrera y pasaria a ser una sugerencia.
     */
    @Test
    fun `una automatica incumplida no se puede saltar marcandola`() {
        val l = lista(bateria = 30f)
        val todasMarcadas = l.map { it.clave }.toSet()

        assertFalse(ListaDeVerificacion.puedeEmpezar(l, todasMarcadas))
        assertEquals(
            listOf(ListaDeVerificacion.BATERIA),
            ListaDeVerificacion.pendientes(l, todasMarcadas).map { it.clave }
        )
    }

    @Test
    fun `la bateria justo en el limite vale`() {
        val l = lista(bateria = ListaDeVerificacion.BATERIA_MINIMA)
        assertTrue(ListaDeVerificacion.puedeEmpezar(l, manuales))
    }

    /**
     * Si el instrumento no responde, la comprobacion NO esta hecha. Darla por
     * buena seria la misma clase de error que dar por bueno el contador de carga
     * del movil 1 sin haberlo medido.
     */
    @Test
    fun `una bateria que no se puede leer no se da por buena`() {
        val l = lista(bateria = null)
        assertFalse(ListaDeVerificacion.puedeEmpezar(l, manuales))
        val bat = l.first { it.clave == ListaDeVerificacion.BATERIA }
        assertEquals("no se pudo leer", bat.detalle)
    }

    /**
     * Sin etiqueta, todas las sesiones saldrian con dispositivoId "?" y el
     * diseño cruzado persona x dispositivo no se podria analizar.
     */
    @Test
    fun `sin etiqueta A o B no se puede empezar`() {
        val l = lista(etiqueta = false)
        assertFalse(ListaDeVerificacion.puedeEmpezar(l, manuales))
        assertTrue(
            ListaDeVerificacion.pendientes(l, manuales)
                .any { it.clave == ListaDeVerificacion.ETIQUETA }
        )
    }

    @Test
    fun `sin participante seleccionado no se puede empezar`() {
        assertFalse(ListaDeVerificacion.puedeEmpezar(lista(participante = false), manuales))
    }

    // ------------------------------------------------------------------
    // Lo que ya no esta
    // ------------------------------------------------------------------

    /**
     * Las cuatro comprobaciones de teclado del plan del 23/08 desaparecieron
     * porque el minijuego usa su propio teclado. Si alguien las reintroduce, es
     * que el teclado volvio a ser el del sistema y hay mucho mas que revisar.
     */
    @Test
    fun `no queda ninguna comprobacion de teclado del sistema`() {
        val textos = lista().joinToString(" ") { it.texto }.lowercase()
        for (palabra in listOf("teclado", "autocorreccion", "predictivo", "deslizamiento")) {
            assertFalse("sobra la comprobacion de '$palabra'", textos.contains(palabra))
        }
    }
}
