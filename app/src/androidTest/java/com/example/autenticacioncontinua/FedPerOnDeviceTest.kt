package com.example.autenticacioncontinua

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.ml.model.ModelManifest
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * Criterios de aprobación de la FASE 2, ejecutados sobre un dispositivo o
 * emulador real.
 *
 * Estas pruebas necesitan que los assets estén generados
 * (`backend/export_tflite_model.py`); sin ellos fallan al cargar el modelo,
 * que es el comportamiento deseado.
 *
 * Ejecutar con:  ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FedPerOnDeviceTest {

    private lateinit var context: Context
    private lateinit var manifest: ModelManifest
    private lateinit var model: TFLiteModelManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manifest = ModelManifest.fromAssets(context)
        model = TFLiteModelManager(context, manifest)
    }

    @After
    fun tearDown() {
        if (::model.isInitialized) model.close()
    }

    // -- Carga e inyección de pesos ------------------------------------

    @Test
    fun elModeloCargaYExponeElVectorDelEncoderConElTamanoAcordado() {
        val encoder = model.getEncoderWeights()
        assertEquals(manifest.encoderFlatSize, encoder.size)
        assertTrue("hay valores no finitos en el encoder", encoder.all { it.isFinite() })
    }

    @Test
    fun inyectarUnVectorPredefinidoNoRompeNiContaminaLaCabeza() {
        val headBefore = model.getHeadWeights()
        val injected = FloatArray(manifest.encoderFlatSize) { 0.01f }

        model.setEncoderWeights(injected)

        assertArrayEqualsExactly(injected, model.getEncoderWeights())
        // El aislamiento entre bloques es lo que hace que esto sea FedPer:
        // FedAvg no puede tocar la cabeza personal.
        assertArrayEqualsExactly(headBefore, model.getHeadWeights())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unVectorDeLongitudEquivocadaSeRechazaAntesDeLlegarAlInterprete() {
        model.setEncoderWeights(FloatArray(manifest.encoderFlatSize - 1))
    }

    // -- Entrenamiento --------------------------------------------------

    @Test
    fun entrenarActualizaEncoderYCabezaYReduceLaPerdida() {
        val encoderBefore = model.getEncoderWeights()
        val headBefore = model.getHeadWeights()

        model.resetOptimizer()
        model.setLearningRate(manifest.learningRate)

        val genuine = separableBatch(manifest.trainGenuinePerBatch, genuine = true)
        val background = separableBatch(manifest.trainBackgroundPerBatch, genuine = false)

        val first = model.trainStep(genuine, background)
        repeat(TRAIN_STEPS) { model.trainStep(genuine, background) }
        val last = model.trainStep(genuine, background)

        assertTrue("pérdida no finita: ${last.loss}", last.loss.isFinite())
        assertTrue("la pérdida no bajó: ${first.loss} -> ${last.loss}", last.loss < first.loss)

        // Si el encoder no se moviera, estaría congelado y FedAvg agregaría
        // siempre los mismos pesos: es el fallo del wrapper de la Celda 15.
        assertNotEquals(
            "el encoder está congelado", 0f,
            maxDelta(encoderBefore, model.getEncoderWeights())
        )
        assertNotEquals(
            "la cabeza no se entrenó", 0f,
            maxDelta(headBefore, model.getHeadWeights())
        )
    }

    @Test
    fun cincoEpocasCompletasCabenEnUnPresupuestoRazonable() {
        val genuine = separableBatch(manifest.trainGenuinePerBatch, genuine = true)
        val background = separableBatch(manifest.trainBackgroundPerBatch, genuine = false)

        // 800 ventanas / 16 por lote = 50 pasos por época, x5 épocas.
        val steps = manifest.localEpochs * STEPS_PER_EPOCH_ESTIMATE
        val startedAt = System.currentTimeMillis()
        repeat(steps) { model.trainStep(genuine, background) }
        val elapsedMs = System.currentTimeMillis() - startedAt

        // No es un umbral de rendimiento sino un detector de patologías: si un
        // paso tarda segundos, el delegado Flex está cayendo a una ruta lenta
        // y ninguna ronda federada terminará dentro del round_timeout.
        val msPerStep = elapsedMs.toDouble() / steps
        println("Entrenamiento: $steps pasos en ${elapsedMs}ms (${msPerStep}ms/paso)")
        assertTrue(
            "Demasiado lento: ${msPerStep}ms por paso de entrenamiento",
            msPerStep < MAX_MS_PER_STEP
        )
    }

    // -- Inferencia ------------------------------------------------------

    @Test
    fun laInferenciaDevuelveProbabilidadesValidas() {
        val window = separableBatch(1, genuine = true).first()
        val result = model.score(window)

        assertTrue(
            "score fuera de [0,1]: ${result.genuineScore}",
            result.genuineScore in 0f..1f
        )
        assertTrue(result.reconstructionError >= 0f)
        assertEquals(result.genuineScore >= manifest.decisionThreshold, result.isGenuine)
    }

    @Test
    fun elLoteDeInferenciaCoincideConLaInferenciaIndividual() {
        val windows = separableBatch(manifest.inferBatch + 5, genuine = true)
        val batchScores = model.scoreBatch(windows)

        assertEquals(windows.size, batchScores.size)
        // El último lote va relleno con ceros: se comprueba que el relleno se
        // descarta y no desplaza los resultados.
        for (i in windows.indices) {
            assertEquals(model.score(windows[i]).genuineScore, batchScores[i], 1e-4f)
        }
    }

    // -- Utilidades ------------------------------------------------------

    private fun separableBatch(count: Int, genuine: Boolean): List<FloatArray> {
        val random = Random(if (genuine) 1 else 2)
        return List(count) {
            FloatArray(manifest.windowFloats) { i ->
                if (genuine) {
                    kotlin.math.sin(i * 0.1).toFloat() + random.nextFloat() * 0.05f
                } else {
                    random.nextFloat() * 2f - 1f
                }
            }
        }
    }

    private fun maxDelta(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in a.indices) max = maxOf(max, abs(a[i] - b[i]))
        return max
    }

    private fun assertArrayEqualsExactly(expected: FloatArray, actual: FloatArray) {
        assertEquals("longitudes distintas", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("difieren en la posición $i", expected[i], actual[i], 0f)
        }
    }

    private companion object {
        const val TRAIN_STEPS = 20
        const val STEPS_PER_EPOCH_ESTIMATE = 50
        const val MAX_MS_PER_STEP = 400.0
    }
}
