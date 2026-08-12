package com.example.autenticacioncontinua.ml.training

import android.util.Log
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.ml.data.BackgroundPool
import com.example.autenticacioncontinua.ml.metrics.BinaryMetrics
import com.example.autenticacioncontinua.ml.model.HeadStore
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.model.ThresholdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Métricas que el cliente devuelve al servidor en una ronda de `evaluate`.
 *
 * @param calibratedThreshold umbral obtenido cruzando ventanas genuinas de
 *   ENTRENAMIENTO con el pool de background de CALIBRACIÓN. Es el único
 *   umbral aplicable en producción; nunca ha visto las etiquetas de este
 *   conjunto.
 */
data class EvaluationResult(
    val loss: Float,
    val numExamples: Int,
    val auc: Double,
    val accuracy: Double,
    val eer: Double,
    val far: Double,
    val frr: Double,
    val calibratedThreshold: Float
)

/**
 * Evaluación local de una ronda federada.
 *
 * Diferencia clave con `AuthClient.evaluate` de `mejor.py`: allí las métricas
 * se calculan sobre `x_test`, el mismo conjunto con el que después se reporta
 * el EER final. Aquí se calculan sobre el conjunto de VALIDACIÓN, y el de
 * test permanece intacto durante toda la federación. El servidor selecciona
 * la mejor ronda con `val_auc`, así que el número que se acabe reportando
 * sobre test sigue siendo ciego.
 *
 * Los impostores de evaluación salen del pool de entrenamiento
 * (`BACKGROUND_TRAIN`), y el umbral se calibra contra el pool DISJUNTO de
 * calibración (`BACKGROUND_CALIB`). Esa separación es lo que impide que el
 * umbral esté ajustado a los mismos impostores contra los que se mide el FAR.
 */
class LocalEvaluator(
    private val modelManager: TFLiteModelManager,
    private val backgroundTrainPool: BackgroundPool,
    private val backgroundCalibPool: BackgroundPool,
    private val headStore: HeadStore,
    private val thresholdStore: ThresholdStore,
    private val metrics: BinaryMetrics
) {
    private val manifest = modelManager.manifest

    suspend fun evaluate(
        globalEncoder: FloatArray,
        evaluationWindows: List<SensorWindow>,
        genuineTrainWindows: List<SensorWindow>,
        seed: Long
    ): EvaluationResult = withContext(Dispatchers.Default) {

        modelManager.setEncoderWeights(globalEncoder)
        headStore.load()?.let { modelManager.setHeadWeights(it) }

        val random = Random(seed)

        // — Conjunto etiquetado: genuinas de validación + impostoras reales —
        val genuineWindows = evaluationWindows.map { it.values }
        val impostorCount = (genuineWindows.size * manifest.backgroundRatio).toInt()
        val impostorWindows = backgroundTrainPool.sample(impostorCount, random)

        val all = genuineWindows + impostorWindows
        val labels = IntArray(all.size) { if (it < genuineWindows.size) 1 else 0 }
        val scores = modelManager.scoreBatch(all)

        // — Umbral calibrado, sin mirar las etiquetas de este conjunto —
        val calibratedThreshold = calibrateThreshold(genuineTrainWindows, random)
        // Se persiste para que la autenticación en tiempo real aplique el
        // mismo umbral que se acaba de medir, en lugar del poblacional por
        // defecto.
        thresholdStore.save(calibratedThreshold)

        val (far, frr) = metrics.farFrr(scores, labels, calibratedThreshold)
        val result = EvaluationResult(
            loss = metrics.binaryCrossEntropy(scores, labels).toFloat(),
            numExamples = all.size,
            auc = metrics.auc(scores, labels),
            accuracy = metrics.accuracy(scores, labels, calibratedThreshold),
            eer = metrics.eer(scores, labels),
            far = far,
            frr = frr,
            calibratedThreshold = calibratedThreshold
        )
        Log.i(
            TAG,
            "val: n=${result.numExamples} auc=${"%.4f".format(result.auc)} " +
                "eer=${"%.4f".format(result.eer)} thr=${"%.3f".format(result.calibratedThreshold)}"
        )
        result
    }

    /**
     * Réplica de `calibrate_threshold_from_background` (mejor.py:1232-1238).
     *
     * Si no hay pool de calibración se cae al umbral por defecto del
     * manifiesto en lugar de reutilizar el pool de entrenamiento: usar los
     * mismos impostores para calibrar y para medir devolvería un FAR
     * optimista que parecería correcto.
     */
    private fun calibrateThreshold(
        genuineTrainWindows: List<SensorWindow>,
        random: Random
    ): Float {
        if (backgroundCalibPool.isEmpty() || genuineTrainWindows.isEmpty()) {
            Log.w(TAG, "Sin pool de calibración: se usa el umbral por defecto")
            return manifest.decisionThreshold
        }
        val genuineScores = modelManager.scoreBatch(genuineTrainWindows.map { it.values })
        val calibWindows = backgroundCalibPool.sample(
            minOf(CALIBRATION_POOL_SIZE, backgroundCalibPool.size), random
        )
        val backgroundScores = modelManager.scoreBatch(calibWindows)
        return metrics.calibrateThreshold(
            genuineScores, backgroundScores, manifest.decisionThreshold
        )
    }

    private companion object {
        const val TAG = "LocalEvaluator"

        /** `calibration_background_pool` de mejor.py. */
        const val CALIBRATION_POOL_SIZE = 400
    }
}
