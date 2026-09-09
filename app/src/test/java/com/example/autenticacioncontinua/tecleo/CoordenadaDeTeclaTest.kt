package com.example.autenticacioncontinua.tecleo

import com.example.autenticacioncontinua.domain.tecleo.CoordenadaDeTecla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que una coordenada contaminada por el multitáctil no llegue a la base.
 *
 * LOS CASOS SON REALES, no inventados: salen de la primera visita completa con
 * dedos, del 31/08 (MANUEL, visita 2, 657 pulsaciones). Se conservan tal cual
 * porque un caso inventado se elige para pasar la prueba, y estos se eligieron
 * solos: son exactamente los 23 que el fallo produjo.
 */
class CoordenadaDeTeclaTest {

    /** Una letra del teclado del estudio en el movil 1: 1080 px / 10 columnas. */
    private val ANCHO = 108
    private val ALTO = 140

    // ------------------------------------------------------------------
    // Lo que hay que conservar
    // ------------------------------------------------------------------

    @Test
    fun `una pulsacion en el centro de la tecla se conserva`() {
        val (x, y) = CoordenadaDeTecla.filtrar(54f, 70f, ANCHO, ALTO)
        assertEquals(54f, x)
        assertEquals(70f, y)
    }

    /**
     * El borde es DATO, no ruido. Pulsar sistematicamente arriba a la izquierda
     * es justo el rasgo que este canal existe para captar, y el valor real
     * medido en esa sesion fue x = −4.8 sobre una tecla de 108.
     */
    @Test
    fun `un toque justo en el borde se conserva aunque salga negativo`() {
        val (x, y) = CoordenadaDeTecla.filtrar(-4.75f, 96.62f, ANCHO, ALTO)
        assertEquals(-4.75f, x)
        assertEquals(96.62f, y)
    }

    @Test
    fun `un toque en la esquina opuesta se conserva`() {
        val (x, _) = CoordenadaDeTecla.filtrar(112f, 138f, ANCHO, ALTO)
        assertEquals(112f, x)
    }

    // ------------------------------------------------------------------
    // Lo que hay que descartar: los 23 casos reales
    // ------------------------------------------------------------------

    @Test
    fun `las coordenadas de otra tecla se descartan`() {
        // (x, y) tal y como quedaron guardadas en `eventos_tecleo` el 31/08.
        val contaminadas = listOf(
            795.50f to -89.00f,     // recibido 's'
            -725.88f to -71.31f,    // recibido 'm'
            478.19f to 510.69f,     // recibido 'e'
            -517.31f to -91.38f,    // recibido 'b'
            641.94f to 277.38f,     // recibido 'e'
            569.06f to 173.19f,     // recibido 'r'
            681.88f to 272.31f,     // recibido 'e'
            561.75f to -72.38f,     // recibido 'd'
            -489.25f to 253.81f,    // recibido 'u'
            -511.50f to 63.38f      // recibido 'i'
        )
        for ((x, y) in contaminadas) {
            val (fx, fy) = CoordenadaDeTecla.filtrar(x, y, ANCHO, ALTO)
            assertNull("($x, $y) venia de otra tecla y se ha colado", fx)
            assertNull("($x, $y) venia de otra tecla y se ha colado", fy)
        }
    }

    /**
     * Y AL REVES: la barra espaciadora mide siete columnas, asi que sus
     * coordenadas legitimas llegan a ~600 px. Si el filtro usara un ancho fijo
     * en vez del de CADA tecla, tiraria todas las pulsaciones del espacio — que
     * en esa sesion fueron 117, la tecla mas pulsada de todas.
     */
    @Test
    fun `la barra espaciadora conserva sus coordenadas grandes`() {
        val anchoEspacio = 756
        for (x in listOf(448.5f, 509.62f, 573.88f, 599.3f)) {
            val (fx, _) = CoordenadaDeTecla.filtrar(x, 40f, anchoEspacio, ALTO)
            assertEquals("el espacio mide $anchoEspacio px y $x le pertenece", x, fx)
        }
        // El mismo valor sobre una LETRA si es contaminacion.
        assertNull(CoordenadaDeTecla.filtrar(509.62f, 40f, ANCHO, ALTO).first)
    }

    // ------------------------------------------------------------------
    // Bordes del propio filtro
    // ------------------------------------------------------------------

    /**
     * Antes de que el layout mida la tecla no hay nada con lo que juzgar. Se
     * acepta: descartar en masa la primera pulsacion de cada sesion seria peor
     * que dejar pasar una posible mala.
     */
    @Test
    fun `sin tamano medido todavia no se descarta nada`() {
        assertTrue(CoordenadaDeTecla.dentro(9999f, -9999f, 0, 0))
        assertEquals(50f, CoordenadaDeTecla.filtrar(50f, 50f, 0, 0).first)
    }

    @Test
    fun `un nulo de entrada sigue siendo nulo`() {
        assertNull(CoordenadaDeTecla.filtrar(null, 50f, ANCHO, ALTO).first)
        assertNull(CoordenadaDeTecla.filtrar(50f, null, ANCHO, ALTO).second)
    }

    /** Media medida es una medida falsa: si cae una, caen las dos. */
    @Test
    fun `si solo una coordenada esta fuera se anulan las dos`() {
        val (x, y) = CoordenadaDeTecla.filtrar(54f, 510.69f, ANCHO, ALTO)
        assertNull("la x era buena pero la y venia de otra fila", x)
        assertNull(y)
    }

    @Test
    fun `el margen deja fuera lo que dista mas de media tecla`() {
        assertTrue(CoordenadaDeTecla.dentro(-53f, 70f, ANCHO, ALTO))
        assertFalse(CoordenadaDeTecla.dentro(-55f, 70f, ANCHO, ALTO))
        assertTrue(CoordenadaDeTecla.dentro(161f, 70f, ANCHO, ALTO))
        assertFalse(CoordenadaDeTecla.dentro(163f, 70f, ANCHO, ALTO))
    }

    /**
     * La cifra que hay que poder declarar en la memoria: cuanto se pierde.
     * En la sesion del 31/08 fueron 23 de 657.
     */
    @Test
    fun `el porcentaje descartado se calcula para poder declararlo`() {
        assertEquals(4, CoordenadaDeTecla.porcentajeDescartado(23, 657))
        assertEquals(0, CoordenadaDeTecla.porcentajeDescartado(0, 657))
        assertEquals(0, CoordenadaDeTecla.porcentajeDescartado(5, 0))
    }
}
