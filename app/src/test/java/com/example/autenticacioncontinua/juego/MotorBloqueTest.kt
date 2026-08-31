package com.example.autenticacioncontinua.juego

import com.example.autenticacioncontinua.domain.juego.MotorBloque
import com.example.autenticacioncontinua.domain.tecleo.DetectorDeConstante
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.domain.textos.Parrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor del bloque: varios párrafos bajo un solo cronómetro.
 *
 * Lo que se comprueba aquí es exactamente lo que acaba en la tabla `bloques` y
 * en `eventos_tecleo` de los 20-30 participantes. La trampa que estas pruebas
 * vigilan es el DOBLE CONTEO: `RegistroDeTecleo.cerrar()` devuelve todos sus
 * eventos, incluidos los que ya salieron uno a uno, así que un descarte mal
 * hecho dobla las pulsaciones del bloque sin que nada falle.
 */
class MotorBloqueTest {

    private fun parrafo(i: Int, texto: String) =
        Parrafo(id = "es_%04d".format(i), idioma = "es", texto = texto)

    private fun motor(vararg textos: String, inicio: Long = 0L) = MotorBloque(
        bloqueId = 7L,
        idioma = "es",
        parrafos = textos.mapIndexed { i, t -> parrafo(i + 1, t) },
        inicioMs = inicio
    )

    /** Una pulsación completa: baja en [tDown] y sube 90 ms después. */
    private fun MotorBloque.teclear(
        c: String,
        tDown: Long,
        presion: Float? = 0.5f,
        area: Float? = 12f
    ) {
        aceptar(
            PulsacionCruda(
                FaseDePulsacion.ABAJO, c, tMs = tDown,
                x = 10f, y = 20f, presion = presion, area = area
            )
        )
        aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, c, tMs = tDown + 90))
    }

    private fun MotorBloque.tecleaTodo(texto: String, desde: Long = 100L, paso: Long = 200L) {
        texto.forEachIndexed { i, c -> teclear(c.toString(), desde + i * paso) }
    }

    // ------------------------------------------------------------------
    // Totales del bloque
    // ------------------------------------------------------------------

    @Test
    fun `los aciertos y errores se cuentan sobre el bloque entero`() {
        val m = motor("hola")
        m.teclear("h", 100)
        m.teclear("x", 300)      // error: tocaba 'o'
        m.teclear("l", 500)
        m.teclear("a", 700)

        val e = m.estado
        assertEquals(4, e.pulsaciones)
        assertEquals(3, e.aciertos)
        assertEquals(1, e.errores)
        assertEquals(0.75f, e.precision, 1e-6f)
    }

    @Test
    fun `el retroceso se cuenta aparte, ni como acierto ni como error`() {
        val m = motor("hola")
        m.teclear("h", 100)
        m.teclear("x", 300)
        m.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "", esRetroceso = true, tMs = 500))
        m.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "", esRetroceso = true, tMs = 560))

        val e = m.estado
        assertEquals(1, e.borrados)
        assertEquals("el borrado no suma pulsacion de caracter", 2, e.pulsaciones)
        assertEquals(1, e.aciertos)
        assertEquals(1, e.errores)
    }

    // ------------------------------------------------------------------
    // Encadenado de párrafos
    // ------------------------------------------------------------------

    /**
     * Un párrafo terminado NO termina el bloque: se encadena el siguiente sin
     * pausa. Sólo el reloj cierra el bloque.
     */
    @Test
    fun `al terminar un parrafo se pasa al siguiente sin cerrar el bloque`() {
        val m = motor("ab", "cd")
        m.tecleaTodo("ab")

        assertEquals("cd", m.estado.texto)
        assertEquals("vuelve al principio del nuevo texto", 0, m.estado.posicion)
        assertEquals(1, m.estado.parrafosCompletados)
        assertEquals("c", m.caracterActual)
    }

    /**
     * LA PRUEBA QUE VIGILA EL DOBLE CONTEO. Con dos párrafos de dos caracteres,
     * las pulsaciones del bloque son cuatro; si el descarte de
     * `RegistroDeTecleo.cerrar()` fallara, saldrían seis u ocho.
     */
    @Test
    fun `los totales se acumulan entre parrafos sin duplicarse`() {
        val m = motor("ab", "cd")
        m.tecleaTodo("abcd")

        val e = m.estado
        assertEquals(4, e.pulsaciones)
        assertEquals(4, e.aciertos)
        assertEquals(1f, e.precision, 1e-6f)
        assertEquals("un evento por tecla, ni uno mas", 4, m.cerrar().size)
    }

    @Test
    fun `los eventos quedan en orden y con su parrafo`() {
        val m = motor("ab", "cd")
        m.tecleaTodo("abcd")

        val eventos = m.cerrar()
        assertEquals(listOf("a", "b", "c", "d"), eventos.map { it.recibido })
        assertEquals(
            listOf("es_0001", "es_0001", "es_0002", "es_0002"),
            eventos.map { it.parrafoId }
        )
        assertEquals(listOf(0, 1, 0, 1), eventos.map { it.posicion })
    }

    /** `parrafosUsados` es lo que se guarda en `bloques.parrafosUsados`. */
    @Test
    fun `solo se declaran usados los parrafos que se llegaron a mostrar`() {
        val m = motor("ab", "cd", "ef")
        assertEquals(listOf("es_0001"), m.parrafosUsados())

        m.tecleaTodo("ab")
        assertEquals(listOf("es_0001", "es_0002"), m.parrafosUsados())

        m.cerrar()
        assertEquals(
            "el tercero nunca se enseno: no se declara",
            listOf("es_0001", "es_0002"),
            m.parrafosUsados()
        )
    }

    /**
     * Si se acaban los párrafos se sigue sobre el último en vez de dejar al
     * participante mirando una pantalla vacía con el cronómetro corriendo.
     * `RegistroDeTecleo` registra igualmente las pulsaciones posteriores al
     * final del texto.
     */
    @Test
    fun `agotados los parrafos se sigue registrando sobre el ultimo`() {
        val m = motor("ab")
        m.tecleaTodo("ab")
        assertNull("no queda caracter que escribir", m.caracterActual)

        m.teclear("z", 900)

        assertEquals(3, m.estado.pulsaciones)
        assertEquals("la de mas no puede acertar", 2, m.estado.aciertos)
        assertEquals(0, m.estado.parrafosCompletados)
    }

    // ------------------------------------------------------------------
    // Cierre
    // ------------------------------------------------------------------

    /**
     * Una tecla que siga pulsada al acabar el bloque se emite con `tUpMs = 0`:
     * dato incompleto, pero declarado. Perderla sesgaría la muestra hacia las
     * pulsaciones cortas, que son las que sí se cerraron a tiempo.
     */
    @Test
    fun `cerrar emite las teclas que seguian pulsadas`() {
        val m = motor("hola")
        m.teclear("h", 100)
        m.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "o", tMs = 300))

        val eventos = m.cerrar()

        assertEquals(2, eventos.size)
        assertEquals(0L, eventos.last().tUpMs)
        assertNull("sin permanencia, y se ve", eventos.last().permanenciaMs)
    }

    /**
     * El bloque puede cerrarse por tiempo y volver a cerrarse desde el `onStop`
     * que llega detrás. La segunda llamada no debe duplicar nada.
     */
    @Test
    fun `cerrar dos veces devuelve lo mismo`() {
        val m = motor("ab")
        m.tecleaTodo("ab")

        val primera = m.cerrar()
        val segunda = m.cerrar()

        assertEquals(primera.size, segunda.size)
        assertEquals(2, segunda.size)
    }

    // ------------------------------------------------------------------
    // Pulsaciones por minuto
    // ------------------------------------------------------------------

    /**
     * La tasa se calcula UNA vez sobre los totales del bloque, no promediando
     * párrafos: un participante rápido que completa nueve párrafos no puede
     * pesar lo mismo que uno lento que completa tres.
     *
     * Cuatro aciertos en 0.6 s reales... se mide contra el último evento, que
     * cae en t=700 ms desde el inicio: 4/5 palabras en 700/60000 minutos.
     */
    @Test
    fun `las pulsaciones por minuto se miden sobre el bloque y con el ultimo evento`() {
        val m = motor("ab", "cd", inicio = 0L)
        m.tecleaTodo("abcd", desde = 100L, paso = 200L)   // ultimo tDown = 700

        val esperado = (4f / 5f) / (700f / 60_000f)
        assertEquals(esperado, m.estado.ppm, 1e-3f)
    }

    @Test
    fun `sin ninguna pulsacion la tasa y la precision son cero, no indefinidas`() {
        val m = motor("hola")
        assertEquals(0f, m.estado.ppm, 0f)
        assertEquals(0f, m.estado.precision, 0f)
        assertEquals(0, m.estado.pulsaciones)
    }

    // ------------------------------------------------------------------
    // Canales de contacto
    // ------------------------------------------------------------------

    /**
     * Los detectores son del BLOQUE, no del párrafo. Reiniciarlos en cada
     * párrafo tiraría la evidencia acumulada y haría que el veredicto sobre si
     * el terminal mide la presión dependiera de dónde cae el corte entre
     * párrafos.
     */
    @Test
    fun `el detector de constante acumula a lo largo de todos los parrafos`() {
        val texto = "abcdefghij"
        val m = motor(texto, texto, texto, texto)
        var t = 0L
        repeat(4) {
            texto.forEach { c -> m.teclear(c.toString(), t, presion = 1.0f); t += 100 }
        }

        assertTrue(
            "40 pulsaciones pasan del minimo de ${DetectorDeConstante.MINIMO_MUESTRAS}",
            m.detectorPresion.muestras >= DetectorDeConstante.MINIMO_MUESTRAS
        )
        assertEquals(
            "presion siempre 1.0: este terminal no la mide",
            true,
            m.detectorPresion.esConstante
        )
    }

    @Test
    fun `una presion que varia no se declara constante`() {
        val texto = "abcdefghij"
        val m = motor(texto, texto, texto, texto)
        var t = 0L
        var p = 0.30f
        repeat(4) {
            texto.forEach { c ->
                m.teclear(c.toString(), t, presion = p)
                p += 0.01f
                t += 100
            }
        }

        assertFalse(m.detectorPresion.esConstante!!)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un bloque sin parrafos no se puede construir`() {
        MotorBloque(bloqueId = 1, idioma = "es", parrafos = emptyList(), inicioMs = 0)
    }
}
