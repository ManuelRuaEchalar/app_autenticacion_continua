package com.example.autenticacioncontinua.ml.data

import com.example.autenticacioncontinua.domain.ml.SensorWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La partición local es la corrección al sesgo metodológico del cuadernillo,
 * así que sus invariantes se comprueban explícitamente: ninguna sesión puede
 * aparecer en dos particiones y la partición no puede cambiar entre rondas.
 */
class DatasetSplitterTest {

    private fun windows(sessionSizes: List<Int>): List<SensorWindow> {
        val out = mutableListOf<SensorWindow>()
        var timestamp = 0L
        sessionSizes.forEachIndexed { session, count ->
            repeat(count) {
                out.add(SensorWindow(FloatArray(4), session, timestamp))
                timestamp += 1_000
            }
        }
        return out
    }

    @Test
    fun `ninguna sesion aparece en dos particiones`() {
        val dataset = DatasetSplitter.split(
            windows(List(10) { 20 }), testRatio = 0.2f, valRatio = 0.2f, seed = 7L
        )

        val trainSessions = dataset.train.map { it.sessionId }.toSet()
        val valSessions = dataset.validation.map { it.sessionId }.toSet()
        val testSessions = dataset.test.map { it.sessionId }.toSet()

        assertTrue("train y val comparten sesión", (trainSessions intersect valSessions).isEmpty())
        assertTrue("train y test comparten sesión", (trainSessions intersect testSessions).isEmpty())
        assertTrue("val y test comparten sesión", (valSessions intersect testSessions).isEmpty())
    }

    @Test
    fun `la particion se conserva entre llamadas con la misma semilla`() {
        val source = windows(List(8) { 25 })
        val first = DatasetSplitter.split(source, 0.2f, 0.2f, seed = 42L)
        val second = DatasetSplitter.split(source, 0.2f, 0.2f, seed = 42L)

        // Si esto fallara, sesiones que hoy son de test entrarían mañana en
        // entrenamiento y el conjunto ciego dejaría de serlo con las rondas.
        assertEquals(first.test.map { it.startTimestampMs }, second.test.map { it.startTimestampMs })
        assertEquals(first.train.map { it.startTimestampMs }, second.train.map { it.startTimestampMs })
    }

    @Test
    fun `semillas distintas producen particiones distintas`() {
        val source = windows(List(10) { 20 })
        val a = DatasetSplitter.split(source, 0.2f, 0.2f, seed = 1L)
        val b = DatasetSplitter.split(source, 0.2f, 0.2f, seed = 99L)
        assertNotEquals(
            a.test.map { it.sessionId }.toSet(),
            b.test.map { it.sessionId }.toSet()
        )
    }

    @Test
    fun `ninguna particion se queda con todas las ventanas`() {
        val dataset = DatasetSplitter.split(
            windows(List(3) { 10 }), testRatio = 0.9f, valRatio = 0.9f, seed = 3L
        )
        assertTrue("train quedó vacío", dataset.train.isNotEmpty())
    }

    @Test
    fun `con una unica sesion se corta por tiempo`() {
        val source = windows(listOf(100))
        val (rest, holdout) = DatasetSplitter.sessionSplit(source, 0.2f, kotlin.random.Random(0))

        assertEquals(80, rest.size)
        assertEquals(20, holdout.size)
        // El holdout es la cola temporal: si se solaparan en el tiempo, las
        // ventanas contiguas compartirían señal.
        assertTrue(holdout.first().startTimestampMs > rest.last().startTimestampMs)
    }

    @Test
    fun `las proporciones se aproximan a lo pedido`() {
        val source = windows(List(20) { 50 })
        val dataset = DatasetSplitter.split(source, testRatio = 0.2f, valRatio = 0.2f, seed = 11L)

        val total = source.size
        val testRatio = dataset.test.size.toDouble() / total
        assertTrue("test = $testRatio", testRatio in 0.15..0.30)

        assertEquals(total, dataset.train.size + dataset.validation.size + dataset.test.size)
    }
}
