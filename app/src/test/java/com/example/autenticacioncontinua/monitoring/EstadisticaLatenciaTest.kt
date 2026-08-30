package com.example.autenticacioncontinua.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas del resumen de latencias.
 *
 * Se comprueba sobre todo la MEDIANA y el p95, que son los que se reportan: las
 * latencias en un movil tienen cola larga —una recoleccion de basura o una
 * bajada de frecuencia del procesador— y la media sola daria una impresion
 * equivocada del comportamiento habitual.
 */
class EstadisticaLatenciaTest {

    @Test
    fun `calcula media, mediana y extremos`() {
        val e = EstadisticaLatencia.desde("inferencia", listOf(10.0, 20.0, 30.0, 40.0, 50.0))
        assertEquals(5, e.n)
        assertEquals(30.0, e.mediaMs, 0.001)
        assertEquals(30.0, e.medianaMs, 0.001)
        assertEquals(10.0, e.minMs, 0.001)
        assertEquals(50.0, e.maxMs, 0.001)
    }

    @Test
    fun `la mediana resiste un valor atipico que arrastra la media`() {
        // Cuatro inferencias de ~10 ms y una pausa de recoleccion de basura.
        val e = EstadisticaLatencia.desde("inferencia", listOf(10.0, 11.0, 10.5, 9.5, 500.0))
        assertEquals(10.5, e.medianaMs, 0.001)
        assertEquals(108.2, e.mediaMs, 0.1)      // la media se va al garete
    }

    @Test
    fun `p95 se interpola y queda cerca del maximo`() {
        val e = EstadisticaLatencia.desde("ronda", (1..100).map { it.toDouble() })
        assertEquals(95.05, e.p95Ms, 0.1)
    }

    @Test
    fun `con una sola muestra todos los estadisticos son ese valor`() {
        val e = EstadisticaLatencia.desde("unica", listOf(42.0))
        assertEquals(42.0, e.mediaMs, 0.001)
        assertEquals(42.0, e.medianaMs, 0.001)
        assertEquals(42.0, e.p95Ms, 0.001)
    }

    @Test
    fun `con dos muestras la mediana interpola entre ambas`() {
        val e = EstadisticaLatencia.desde("dos", listOf(10.0, 20.0))
        assertEquals(15.0, e.medianaMs, 0.001)
    }

    @Test
    fun `no depende del orden de llegada`() {
        val a = EstadisticaLatencia.desde("x", listOf(50.0, 10.0, 30.0, 20.0, 40.0))
        val b = EstadisticaLatencia.desde("x", listOf(10.0, 20.0, 30.0, 40.0, 50.0))
        assertEquals(b.medianaMs, a.medianaMs, 0.001)
        assertEquals(b.p95Ms, a.p95Ms, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resumir una serie vacia es un error de programacion`() {
        EstadisticaLatencia.desde("x", emptyList())
    }
}
