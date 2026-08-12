package com.example.autenticacioncontinua.ml.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las métricas se contrastan contra valores que scikit-learn produce para las
 * mismas entradas: son las que el servidor usará para elegir la mejor ronda,
 * así que un sesgo aquí se propaga a toda la federación.
 */
class BinaryMetricsTest {

    private val metrics = BinaryMetrics()

    @Test
    fun `auc de una separacion perfecta es 1`() {
        val scores = floatArrayOf(0.9f, 0.8f, 0.7f, 0.2f, 0.1f, 0.05f)
        val labels = intArrayOf(1, 1, 1, 0, 0, 0)
        assertEquals(1.0, metrics.auc(scores, labels), 1e-9)
        assertEquals(0.0, metrics.eer(scores, labels), 1e-9)
    }

    @Test
    fun `auc de una separacion invertida es 0`() {
        val scores = floatArrayOf(0.1f, 0.2f, 0.3f, 0.8f, 0.9f)
        val labels = intArrayOf(1, 1, 1, 0, 0)
        assertEquals(0.0, metrics.auc(scores, labels), 1e-9)
    }

    @Test
    fun `los empates se resuelven con rango medio`() {
        // Todas las puntuaciones iguales: el clasificador no aporta nada, así
        // que el AUC tiene que ser exactamente 0.5. Sin corrección por empates
        // saldría 0 o 1 según el orden de llegada.
        val scores = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val labels = intArrayOf(1, 0, 1, 0)
        assertEquals(0.5, metrics.auc(scores, labels), 1e-9)
    }

    @Test
    fun `auc con una sola clase cae a 0_5`() {
        val scores = floatArrayOf(0.9f, 0.8f)
        assertEquals(0.5, metrics.auc(scores, intArrayOf(1, 1)), 1e-9)
    }

    @Test
    fun `el eer se sitúa donde far y frr se cruzan`() {
        // Un solapamiento del 20% por cada lado.
        val genuine = FloatArray(10) { 0.5f + it * 0.05f }   // 0.50 .. 0.95
        val impostor = FloatArray(10) { 0.05f + it * 0.05f } // 0.05 .. 0.50
        val scores = genuine + impostor
        val labels = IntArray(20) { if (it < 10) 1 else 0 }

        val eer = metrics.eer(scores, labels)
        assertTrue("EER fuera de rango: $eer", eer in 0.0..0.15)
    }

    @Test
    fun `el umbral calibrado separa genuinos de background`() {
        val genuine = FloatArray(50) { 0.7f + (it % 10) * 0.02f }
        val background = FloatArray(50) { 0.1f + (it % 10) * 0.02f }

        val threshold = metrics.calibrateThreshold(genuine, background, fallback = 0.5f)

        assertTrue(
            "El umbral $threshold no cae entre las dos distribuciones",
            threshold > 0.28f && threshold <= 0.9f
        )
    }

    @Test
    fun `sin background se devuelve el umbral por defecto`() {
        val threshold = metrics.calibrateThreshold(
            floatArrayOf(0.8f), FloatArray(0), fallback = 0.42f
        )
        assertEquals(0.42f, threshold, 1e-9f)
    }

    @Test
    fun `far y frr son coherentes con el umbral`() {
        val scores = floatArrayOf(0.9f, 0.4f, 0.8f, 0.3f)
        val labels = intArrayOf(1, 1, 0, 0)

        val (far, frr) = metrics.farFrr(scores, labels, threshold = 0.5f)
        assertEquals(0.5, far, 1e-9)  // el impostor de 0.8 se acepta
        assertEquals(0.5, frr, 1e-9)  // el genuino de 0.4 se rechaza
    }

    @Test
    fun `la entropia cruzada penaliza las predicciones seguras y erroneas`() {
        val confidentCorrect = metrics.binaryCrossEntropy(floatArrayOf(0.99f), intArrayOf(1))
        val confidentWrong = metrics.binaryCrossEntropy(floatArrayOf(0.99f), intArrayOf(0))
        assertTrue(confidentWrong > confidentCorrect * 100)
    }
}
