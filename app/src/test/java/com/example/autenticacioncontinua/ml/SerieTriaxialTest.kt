package com.example.autenticacioncontinua.ml

import com.example.autenticacioncontinua.domain.ml.SerieTriaxial
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la serie en arrays primitivos.
 *
 * Lo que se cubre aquí no es "un array guarda lo que le metes" sino las tres
 * propiedades de las que depende el ventaneo: que crecer no pierda ni desordene
 * muestras, que lo entregado tenga exactamente el tamaño lleno —y no la
 * capacidad reservada, que traería ceros al final y el interpolador los leería
 * como señal—, y que una serie con el tiempo hacia atrás se detecte en vez de
 * interpolarse.
 */
class SerieTriaxialTest {

    @Test
    fun `conserva las muestras en orden al crecer muchas veces`() {
        // Capacidad inicial deliberadamente ridícula frente a lo que se mete:
        // fuerza varias reasignaciones, que es donde se perderían muestras.
        val serie = SerieTriaxial(capacidadInicial = 1)
        val n = 5_000
        repeat(n) { i -> serie.anadir(i.toLong(), i.toFloat(), -i.toFloat(), i * 2f) }

        assertEquals(n, serie.size)
        val t = serie.tiempos()
        val x = serie.x()
        val z = serie.z()
        assertEquals(n, t.size)
        for (i in 0 until n) {
            assertEquals(i.toLong(), t[i])
            assertEquals(i.toFloat(), x[i], 0f)
            assertEquals(i * 2f, z[i], 0f)
        }
    }

    @Test
    fun `lo entregado tiene el tamano lleno y no la capacidad reservada`() {
        // El COUNT dimensiona la serie por lo alto: el filtro de tramos
        // etiquetados quita muestras después. Si `tiempos()` devolviera el
        // array entero, la cola de ceros entraría en el interpolador como
        // instantes válidos del año 1970 y rompería la detección de sesiones.
        val serie = SerieTriaxial(capacidadInicial = 1_000)
        serie.anadir(10, 1f, 2f, 3f)
        serie.anadir(20, 4f, 5f, 6f)

        assertEquals(2, serie.size)
        assertArrayEquals(longArrayOf(10, 20), serie.tiempos())
        assertArrayEquals(floatArrayOf(1f, 4f), serie.x(), 0f)
        assertArrayEquals(floatArrayOf(2f, 5f), serie.y(), 0f)
        assertArrayEquals(floatArrayOf(3f, 6f), serie.z(), 0f)
    }

    @Test
    fun `una serie vacia se declara vacia y no rompe al entregarse`() {
        val serie = SerieTriaxial(capacidadInicial = 64)

        assertTrue(serie.isEmpty)
        assertEquals(0, serie.size)
        assertEquals(0, serie.tiempos().size)
        assertEquals(Long.MIN_VALUE, serie.ultimoInstante)
    }

    @Test
    fun `el ultimo instante es el de la ultima muestra`() {
        // Es lo que el repositorio usa para pedir el bloque siguiente. Si
        // devolviera otra cosa, la paginación por clave saltaría muestras o
        // repetiría el mismo bloque para siempre.
        val serie = SerieTriaxial()
        serie.anadir(100, 0f, 0f, 0f)
        serie.anadir(250, 0f, 0f, 0f)

        assertEquals(250, serie.ultimoInstante)
    }

    @Test
    fun `instantes repetidos siguen siendo monotonos`() {
        // A 50 Hz no debería pasar, pero las ráfagas del sensor llegan
        // agrupadas y el reloj de milisegundos se repite. Repetir no es
        // retroceder: la serie sigue siendo utilizable.
        val serie = SerieTriaxial()
        serie.anadir(100, 0f, 0f, 0f)
        serie.anadir(100, 1f, 1f, 1f)
        serie.anadir(101, 2f, 2f, 2f)

        assertTrue(serie.esMonotona())
    }

    @Test
    fun `un retroceso del reloj se detecta`() {
        val serie = SerieTriaxial()
        serie.anadir(100, 0f, 0f, 0f)
        serie.anadir(99, 0f, 0f, 0f)

        assertFalse(serie.esMonotona())
    }

    @Test
    fun `una capacidad inicial absurda no revienta`() {
        // `contarDesde` puede devolver 0 en un terminal recién instalado.
        val serie = SerieTriaxial(capacidadInicial = 0)
        serie.anadir(1, 1f, 1f, 1f)

        assertEquals(1, serie.size)
        assertEquals(1L, serie.tiempos()[0])
    }
}
