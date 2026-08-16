package com.example.autenticacioncontinua.domain.ml

import com.example.autenticacioncontinua.domain.model.LabeledSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El fallo que estas pruebas vigilan no produce excepción ni síntoma visible:
 * si la exclusión deja pasar un tramo, el modelo personal se entrena con datos
 * de otra persona etiquetados como propios y las métricas dejan de significar
 * lo que dicen. Por eso se prueba aquí, sobre lógica pura, y no sólo a mano en
 * el dispositivo.
 */
class ExclusionEtiquetadaTest {

    private fun sesion(
        id: Long,
        start: Long,
        end: Long,
        isOwner: Boolean = false
    ) = LabeledSession(
        id = id,
        participantId = "P$id",
        startMs = start,
        endMs = end,
        isOwner = isOwner,
        note = ""
    )

    @Test
    fun `las rafagas del dueno no se excluyen`() {
        val intervalos = ExclusionEtiquetada.intervalos(
            listOf(sesion(1, 1_000, 2_000, isOwner = true))
        )
        assertTrue("El control del dueño es material genuino suyo", intervalos.isEmpty())
        assertFalse(ExclusionEtiquetada.contiene(intervalos, 1_500))
    }

    @Test
    fun `las rafagas de impostor se excluyen, extremos incluidos`() {
        val intervalos = ExclusionEtiquetada.intervalos(listOf(sesion(1, 1_000, 2_000)))

        assertFalse(ExclusionEtiquetada.contiene(intervalos, 999))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 1_000))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 1_500))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 2_000))
        assertFalse(ExclusionEtiquetada.contiene(intervalos, 2_001))
    }

    @Test
    fun `busca en el intervalo correcto con varios tramos`() {
        // Se pasan desordenados: vienen de la base ordenados por startMs, pero
        // la búsqueda binaria de `contiene` exige orden y no puede depender de
        // que quien llame lo haya respetado.
        val intervalos = ExclusionEtiquetada.intervalos(
            listOf(
                sesion(3, 9_000, 10_000),
                sesion(1, 1_000, 2_000),
                sesion(2, 5_000, 6_000)
            )
        )
        assertEquals(3, intervalos.size)

        assertTrue(ExclusionEtiquetada.contiene(intervalos, 1_500))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 5_500))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 9_500))

        // Los huecos ENTRE tramos son del dueño y deben sobrevivir.
        assertFalse(ExclusionEtiquetada.contiene(intervalos, 3_000))
        assertFalse(ExclusionEtiquetada.contiene(intervalos, 7_000))
        assertFalse(ExclusionEtiquetada.contiene(intervalos, 20_000))
    }

    @Test
    fun `un tramo sin cerrar se excluye hasta la duracion maxima`() {
        // endMs = 0: la app murió a mitad de la captura. Los datos SÍ se
        // escribieron, así que el tramo tiene que seguir excluyéndose.
        val abierta = sesion(1, 1_000, 0)
        assertTrue(abierta.enCurso)

        val intervalos = ExclusionEtiquetada.intervalos(listOf(abierta))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 1_000))
        assertTrue(
            "Debe cubrir toda la duración supuesta",
            ExclusionEtiquetada.contiene(intervalos, 1_000 + LabeledSession.MAX_ABIERTA_MS)
        )
        assertFalse(
            ExclusionEtiquetada.contiene(intervalos, 1_001 + LabeledSession.MAX_ABIERTA_MS)
        )
    }

    @Test
    fun `los tramos solapados se fusionan en uno`() {
        // Lo provoca un tramo abierto que se extiende sobre el siguiente. Sin
        // fusionar, la búsqueda binaria puede aterrizar en el intervalo
        // equivocado y dar por bueno un instante que sí está cubierto.
        val intervalos = ExclusionEtiquetada.intervalos(
            listOf(
                sesion(1, 1_000, 0),                  // 1.000 .. 901.000
                sesion(2, 500_000, 520_000)           // dentro del anterior
            )
        )
        assertEquals(1, intervalos.size)
        assertEquals(1_000L, intervalos.first().first)
        assertEquals(1_000 + LabeledSession.MAX_ABIERTA_MS, intervalos.first().last)
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 510_000))
    }

    @Test
    fun `sin sesiones etiquetadas no se excluye nada`() {
        val intervalos = ExclusionEtiquetada.intervalos(emptyList())
        assertTrue(intervalos.isEmpty())
        assertFalse(ExclusionEtiquetada.contiene(intervalos, System.currentTimeMillis()))
    }

    @Test
    fun `mezcla de dueno e impostores deja pasar solo lo del dueno`() {
        val intervalos = ExclusionEtiquetada.intervalos(
            listOf(
                sesion(1, 1_000, 2_000, isOwner = false),
                sesion(2, 3_000, 4_000, isOwner = true),
                sesion(3, 5_000, 6_000, isOwner = false)
            )
        )
        assertEquals(2, intervalos.size)
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 1_500))
        assertFalse("La ráfaga de control del dueño se conserva",
            ExclusionEtiquetada.contiene(intervalos, 3_500))
        assertTrue(ExclusionEtiquetada.contiene(intervalos, 5_500))
    }
}
