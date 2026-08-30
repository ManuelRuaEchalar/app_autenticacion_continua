package com.example.autenticacioncontinua.tecleo

import com.example.autenticacioncontinua.data.tecleo.RegistroDeTecleo
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del motor del minijuego.
 *
 * De esta clase salen la precisión y las pulsaciones por minuto de los 20-30
 * participantes, y los tiempos de permanencia de cada tecla — que es la
 * magnitud de la dinámica de tecleo que menos depende del texto copiado. Un
 * fallo aquí no rompe nada visible: produce números plausibles y equivocados.
 */
class RegistroDeTecleoTest {

    private fun registro(texto: String = "hola") =
        RegistroDeTecleo(bloqueId = 1, parrafoId = "es_001", textoEsperado = texto, inicioMs = 0)

    /** Una pulsación completa: baja en [tDown] y sube en [tUp]. */
    private fun RegistroDeTecleo.teclear(
        caracter: String,
        tDown: Long,
        tUp: Long = tDown + 90,
        presion: Float? = 0.5f,
        area: Float? = 12f
    ) {
        aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, caracter, tMs = tDown,
            x = 10f, y = 20f, presion = presion, area = area))
        aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, caracter, tMs = tUp))
    }

    private fun RegistroDeTecleo.borrar(tDown: Long, tUp: Long = tDown + 80) {
        aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "", esRetroceso = true, tMs = tDown))
        aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "", esRetroceso = true, tMs = tUp))
    }

    // ------------------------------------------------------------------
    // Secuencia y orden
    // ------------------------------------------------------------------

    @Test
    fun `una secuencia correcta produce un evento por tecla, en orden`() {
        val r = registro("hola")
        r.teclear("h", 100); r.teclear("o", 300); r.teclear("l", 500); r.teclear("a", 700)

        val e = r.cerrar()
        assertEquals(4, e.size)
        assertEquals(listOf("h", "o", "l", "a"), e.map { it.recibido })
        assertEquals(listOf(0, 1, 2, 3), e.map { it.posicion })
        assertEquals(listOf(100L, 300L, 500L, 700L), e.map { it.tDownMs })
        assertTrue(e.all { it.acierto })
        assertTrue(r.estado.terminado)
    }

    /**
     * El evento sólo está completo al soltar: de `tUp − tDown` sale el tiempo de
     * permanencia. Emitirlo al pulsar dejaría esa magnitud sin calcular.
     */
    @Test
    fun `el evento se cierra al soltar, no al pulsar`() {
        val r = registro("h")
        assertNull(r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "h", tMs = 100)))
        val e = r.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "h", tMs = 190))
        assertEquals(100L, e!!.tDownMs)
        assertEquals(190L, e.tUpMs)
        assertEquals(90L, e.permanenciaMs)
    }

    /**
     * Tecleo a dos manos: dos teclas pueden estar pulsadas a la vez y soltarse
     * en orden distinto al de pulsación. Cada `up` debe cerrar SU `down`.
     */
    @Test
    fun `dos teclas solapadas se emparejan por tecla, no por orden`() {
        val r = registro("ho")
        r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "h", tMs = 100))
        r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "o", tMs = 150))
        r.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "o", tMs = 200))
        r.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "h", tMs = 260))

        val e = r.cerrar()
        val h = e.first { it.recibido == "h" }
        val o = e.first { it.recibido == "o" }
        assertEquals(160L, h.permanenciaMs)
        assertEquals(50L, o.permanenciaMs)
    }

    /**
     * Un `up` cuyo `down` no se vio —el dedo entró en la tecla desde fuera—
     * se descarta. Inventarle un `tDown` contaminaría la permanencia, que es la
     * magnitud más valiosa.
     */
    @Test
    fun `un soltar sin su pulsar se descarta en vez de inventar un tiempo`() {
        val r = registro("h")
        assertNull(r.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "h", tMs = 200)))
        assertTrue(r.cerrar().isEmpty())
        assertEquals(0, r.estado.pulsaciones)
    }

    // ------------------------------------------------------------------
    // Aciertos, errores y borrados
    // ------------------------------------------------------------------

    @Test
    fun `un error avanza y queda marcado, sin bloquear al participante`() {
        val r = registro("hola")
        r.teclear("h", 100); r.teclear("x", 300); r.teclear("l", 500)

        val s = r.estado
        assertEquals(3, s.pulsaciones)
        assertEquals(2, s.aciertos)
        assertEquals(1, s.errores)
        assertEquals(setOf(1), s.fallados)
        assertEquals(3, s.posicion)          // avanzó pese al error
    }

    /**
     * El borrado NO es un error ni un acierto. Dos participantes con la misma
     * precisión final pueden tener dinámicas opuestas —uno que no falla y otro
     * que falla y corrige— y eso hay que poder verlo.
     */
    @Test
    fun `el retroceso se registra como borrado, no como acierto ni como error`() {
        val r = registro("hola")
        r.teclear("h", 100); r.teclear("x", 300); r.borrar(500); r.teclear("o", 700)

        val s = r.estado
        assertEquals(1, s.borrados)
        assertEquals(1, s.errores)
        assertEquals(2, s.aciertos)
        assertEquals(3, s.pulsaciones)       // el borrado no cuenta como pulsación de texto
        assertEquals(2, s.posicion)
        assertTrue("al corregir, la posición deja de estar fallada", s.fallados.isEmpty())

        val borrado = r.cerrar().single { it.borrado }
        assertFalse(borrado.acierto)
        assertEquals("", borrado.esperado)
        assertEquals("", borrado.recibido)
    }

    @Test
    fun `el retroceso al principio se registra aunque no haya nada que borrar`() {
        val r = registro("hola")
        r.borrar(100)

        assertEquals(1, r.estado.borrados)
        assertEquals(0, r.estado.posicion)
        assertEquals(1, r.cerrar().size)
    }

    @Test
    fun `borrar y reescribir bien deja el texto correcto`() {
        val r = registro("hola")
        r.teclear("h", 100); r.teclear("x", 200); r.borrar(300); r.teclear("o", 400)
        r.teclear("l", 500); r.teclear("a", 600)

        val s = r.estado
        assertEquals(4, s.posicion)
        assertTrue(s.terminado)
        assertEquals(4, s.aciertos)
        assertEquals(1, s.errores)
        assertEquals(1, s.borrados)
    }

    @Test
    fun `teclear despues del final del parrafo se registra pero no avanza`() {
        val r = registro("ho")
        r.teclear("h", 100); r.teclear("o", 200); r.teclear("z", 300)

        val s = r.estado
        assertEquals(2, s.posicion)
        assertEquals(3, s.pulsaciones)
        assertEquals(2, s.aciertos)
        val ultimo = r.cerrar().last()
        assertEquals("", ultimo.esperado)
        assertEquals("z", ultimo.recibido)
        assertFalse(ultimo.acierto)
    }

    // ------------------------------------------------------------------
    // Puntuación
    // ------------------------------------------------------------------

    @Test
    fun `precision es aciertos sobre pulsaciones, sin contar borrados`() {
        val r = registro("holaa")
        r.teclear("h", 100); r.teclear("x", 200); r.teclear("l", 300); r.teclear("a", 400)
        r.borrar(500)

        // 3 aciertos de 4 pulsaciones; el borrado no entra en el denominador.
        assertEquals(0.75f, r.estado.precision, 0.001f)
    }

    @Test
    fun `ppm usa la convencion de cinco caracteres por palabra`() {
        val r = registro("abcdefghij")
        // 10 aciertos en 60 s -> 10/5 = 2 palabras por minuto.
        for (i in 0 until 10) r.teclear(('a' + i).toString(), (i + 1) * 6_000L)

        assertEquals(10, r.estado.aciertos)
        assertEquals(2f, r.estado.ppmNeta, 0.01f)
    }

    /**
     * La ppm neta descuenta los errores; la bruta no. Reportar sólo la bruta
     * premiaría a quien teclea rápido y mal.
     */
    @Test
    fun `ppm neta descuenta los errores y la bruta no`() {
        val r = registro("abcdefghij")
        for (i in 0 until 10) {
            val c = if (i < 5) ('a' + i).toString() else "z"    // 5 bien, 5 mal
            r.teclear(c, (i + 1) * 6_000L)
        }
        assertEquals(2f, r.estado.ppmBruta, 0.01f)
        assertEquals(1f, r.estado.ppmNeta, 0.01f)
    }

    /**
     * Dos consultas seguidas sobre el mismo estado tienen que dar el mismo
     * número. Si la ppm se calculara con «ahora», la cifra guardada dependería
     * de cuándo se consultó.
     */
    @Test
    fun `la ppm es reproducible entre consultas`() {
        val r = registro("abc")
        r.teclear("a", 6_000); r.teclear("b", 12_000)
        assertEquals(r.estado.ppmNeta, r.estado.ppmNeta, 0.0001f)
    }

    @Test
    fun `sin pulsaciones la puntuacion es cero y no una division por cero`() {
        val s = registro("hola").estado
        assertEquals(0f, s.ppmBruta, 0f)
        assertEquals(0f, s.precision, 0f)
        assertFalse(s.terminado)
    }

    // ------------------------------------------------------------------
    // Canales de contacto
    // ------------------------------------------------------------------

    /**
     * Presión y área se toman del ABAJO: es el instante del impacto. Al soltar,
     * el dedo ya se está separando y el área se desploma.
     */
    @Test
    fun `presion y area salen del instante de pulsar`() {
        val r = registro("h")
        r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "h", tMs = 100,
            x = 33f, y = 44f, presion = 0.7f, area = 15f))
        val e = r.aceptar(PulsacionCruda(FaseDePulsacion.ARRIBA, "h", tMs = 190,
            presion = 0.05f, area = 2f))!!

        assertEquals(0.7f, e.presion!!, 0.001f)
        assertEquals(15f, e.area!!, 0.001f)
        assertEquals(33f, e.x!!, 0.001f)
    }

    /**
     * Hay terminales que devuelven presión 1.0 siempre. Guardar esa constante
     * como si fuera una medida haría creer al análisis que hay una variable
     * donde sólo hay una decisión del fabricante.
     */
    @Test
    fun `detecta un terminal que devuelve presion constante`() {
        val r = registro("a".repeat(40))
        for (i in 0 until 40) r.teclear("a", i * 200L, presion = 1.0f, area = 10f)

        assertEquals(true, r.detectorPresion.esConstante)
        assertTrue(r.detectorPresion.informe().contains("CONSTANTE"))
    }

    @Test
    fun `un terminal que si mide la presion no se marca como constante`() {
        val r = registro("a".repeat(40))
        for (i in 0 until 40) r.teclear("a", i * 200L, presion = 0.3f + i * 0.01f)

        assertEquals(false, r.detectorPresion.esConstante)
    }

    /**
     * Con pocas pulsaciones el veredicto es «todavía no se sabe», que es
     * distinto de «es constante»: un participante muy uniforme podría parecer
     * un terminal que no mide.
     */
    @Test
    fun `con pocas muestras el detector no dictamina`() {
        val r = registro("aaa")
        for (i in 0 until 3) r.teclear("a", i * 200L, presion = 1.0f)

        assertNull(r.detectorPresion.esConstante)
    }

    @Test
    fun `un canal ausente no se confunde con uno constante`() {
        val r = registro("a".repeat(40))
        for (i in 0 until 40) r.teclear("a", i * 200L, presion = null, area = null)

        assertEquals(0, r.detectorPresion.muestras)
        assertNull(r.detectorPresion.esConstante)
    }

    // ------------------------------------------------------------------
    // Cierre
    // ------------------------------------------------------------------

    /**
     * Perder las teclas sin soltar sesgaría la muestra hacia las pulsaciones
     * cortas, que son justo las que sí se cerraron a tiempo.
     */
    @Test
    fun `una tecla aun pulsada al cerrar se emite con tUp a cero`() {
        val r = registro("ho")
        r.teclear("h", 100)
        r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "o", tMs = 300))

        val e = r.cerrar()
        assertEquals(2, e.size)
        val sinCerrar = e.last()
        assertEquals(300L, sinCerrar.tDownMs)
        assertEquals(0L, sinCerrar.tUpMs)
        assertNull("sin tUp no hay permanencia calculable", sinCerrar.permanenciaMs)
    }

    @Test
    fun `cerrar dos veces no duplica las pulsaciones pendientes`() {
        val r = registro("ho")
        r.aceptar(PulsacionCruda(FaseDePulsacion.ABAJO, "h", tMs = 100))

        assertEquals(1, r.cerrar().size)
        assertEquals(1, r.cerrar().size)
    }

    @Test
    fun `todos los eventos llevan el bloque y el parrafo del que salen`() {
        val r = RegistroDeTecleo(bloqueId = 42, parrafoId = "la_007",
            textoEsperado = "ab", inicioMs = 0)
        r.teclear("a", 100); r.teclear("b", 200)

        val e = r.cerrar()
        assertTrue(e.all { it.bloqueId == 42L && it.parrafoId == "la_007" })
    }
}
