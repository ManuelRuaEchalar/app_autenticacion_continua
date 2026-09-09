package com.example.autenticacioncontinua.export

import com.example.autenticacioncontinua.domain.export.FormatoCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * El CSV se vuelve a leer idéntico. Es la primera prueba que pide la fase 9.
 */
class FormatoCsvTest {

    @Test
    fun `una celda normal no se toca`() {
        assertEquals("P01", FormatoCsv.celda("P01"))
        assertEquals("es", FormatoCsv.celda("es"))
    }

    /**
     * EL CORPUS TIENE COMAS Y COMILLAS: el teclado del estudio tiene tecla de
     * coma y de punto, y `esperado`/`recibido` guardan literalmente el carácter.
     * Sin escapado, una coma tecleada parte la fila y desplaza todas las
     * columnas siguientes — sin que el fichero deje de parecer válido.
     */
    @Test
    fun `una coma tecleada no parte la fila`() {
        val fila = FormatoCsv.fila(listOf(FormatoCsv.celda(","), FormatoCsv.celda("a")))
        val leida = FormatoCsv.leerFila(fila.trimEnd('\n'))
        assertEquals(2, leida.size)
        assertEquals(",", leida[0])
        assertEquals("a", leida[1])
    }

    @Test
    fun `las comillas se duplican y se recuperan`() {
        val original = """di "hola" y se fue"""
        val leida = FormatoCsv.leerFila(FormatoCsv.celda(original))
        assertEquals(original, leida[0])
    }

    @Test
    fun `un salto de linea dentro de una celda no parte la fila`() {
        val leida = FormatoCsv.leerFila(FormatoCsv.celda("a\nb"))
        assertEquals("a\nb", leida[0])
    }

    /**
     * EL FALLO MÁS CARO Y EL MÁS INVISIBLE. En un teléfono con configuración
     * española `Float.toString()` puede escribir la coma decimal, y dentro de un
     * CSV separado por comas eso corre todas las columnas a partir de ahí. El
     * fichero se abre, tiene las filas correctas, y los datos están mal.
     */
    @Test
    fun `los decimales llevan punto sea cual sea la configuracion regional`() {
        val previa = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("9.64", FormatoCsv.celda(9.64f))
            assertEquals("0.8975", FormatoCsv.celda(0.8975f))
            assertEquals("-1.5E-4", FormatoCsv.celda(-1.5E-4f).uppercase())
        } finally {
            Locale.setDefault(previa)
        }
    }

    /**
     * `presion` y `area` son nulos cuando el terminal no los mide, y `x`/`y`
     * cuando la coordenada venía de otra tecla. Un nulo leído como 0 diría «no
     * hay presión» donde el dato es «no se sabe»: sería un dato inventado.
     */
    @Test
    fun `el nulo se distingue del cero y del vacio`() {
        assertEquals("", FormatoCsv.celda(null as Float?))
        assertEquals("0.0", FormatoCsv.celda(0.0f))
        val leida = FormatoCsv.leerFila(
            FormatoCsv.fila(
                listOf(FormatoCsv.celda(null as Float?), FormatoCsv.celda(0f), FormatoCsv.celda(""))
            ).trimEnd('\n')
        )
        assertNull("el nulo tiene que volver como nulo", leida[0])
        assertEquals("0.0", leida[1])
        assertEquals("", leida[2])
    }

    @Test
    fun `el booleano va como 0 y 1, que es lo que ya guarda SQLite`() {
        assertEquals("1", FormatoCsv.celda(true))
        assertEquals("0", FormatoCsv.celda(false))
        assertEquals("", FormatoCsv.celda(null as Boolean?))
    }

    @Test
    fun `un csv entero se lee fila a fila`() {
        val texto = buildString {
            append(FormatoCsv.fila(listOf("a", "b", "c")))
            append(FormatoCsv.fila(listOf("1", "2", "3")))
            append(FormatoCsv.fila(listOf(FormatoCsv.celda(","), "", "x")))
        }
        assertEquals(listOf("a", "b", "c"), FormatoCsv.cabecera(texto))
        val filas = FormatoCsv.leer(texto)
        assertEquals(2, filas.size)
        assertEquals(listOf("1", "2", "3"), filas[0])
        assertEquals(",", filas[1][0])
        assertNull(filas[1][1])
    }
}
