package com.example.autenticacioncontinua.ml.training

import android.util.Log
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.ml.data.BackgroundPool
import com.example.autenticacioncontinua.ml.data.WindowEnergy
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
 *   CALIBRACIÓN (no vistas en entrenamiento) con el pool de background de
 *   CALIBRACIÓN. Es el único umbral aplicable en producción; nunca ha visto
 *   las etiquetas de este conjunto.
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
 *
 * EL LADO GENUINO NECESITA LA MISMA SEPARACIÓN, y no la tenía (pendiente B2).
 * Calibrar con `local.train` —ventanas con las que el modelo ACABA de
 * entrenar— sitúa el cruce FAR/FRR donde están las puntuaciones
 * sobreajustadas, ~1.0. En una sesión distinta las genuinas puntúan más bajo y
 * caen todas por debajo: medido el 2026-08-11, `far=0.0 frr=1.0
 * umbral=0.9984564`, o sea un sistema que rechaza al usuario legítimo el 100%
 * de las veces. El EER sobrevivía sólo porque barre la ROC y no depende del
 * umbral.
 *
 * Por eso [evaluate] recibe [calibrationWindows] aparte del conjunto medido.
 * Quien llama debe pasar genuinas que cumplan LAS DOS condiciones:
 *   1. no vistas en entrenamiento (si no, vuelve el FRR=1.0), y
 *   2. disjuntas del conjunto que se mide (si no, el FRR sale optimista).
 * Para la medición final sobre test eso es exactamente `validation`.
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
        calibrationWindows: List<SensorWindow>,
        emparejarImpostores: Boolean = true,
        seed: Long
    ): EvaluationResult = withContext(Dispatchers.Default) {

        modelManager.setEncoderWeights(globalEncoder)
        headStore.load()?.let { modelManager.setHeadWeights(it) }

        // El emparejado produce UNA impostora por genuina, así que sólo es
        // equivalente al muestreo anterior si el ratio es 1.0. Se comprueba en
        // vez de asumirlo: este proyecto ya perdió 18 rondas por un contrato
        // que dejó de cumplirse sin que nadie se enterara.
        require(manifest.backgroundRatio == 1.0f) {
            "El muestreo emparejado asume background_ratio=1.0, pero el " +
                "manifiesto declara ${manifest.backgroundRatio}. Ajusta " +
                "sampleMatched para respetar el ratio antes de cambiarlo."
        }

        val random = Random(seed)

        // — Conjunto etiquetado: genuinas de validación + impostoras reales —
        // Las impostoras se emparejan por energía con las genuinas: sin eso,
        // una parte del AUC sale de que HMOG se movía más (medido: 0.72-0.81
        // sólo con la energía del acelerómetro), no de biometría. Ver
        // BackgroundPool.sampleMatched.
        val genuineWindows = evaluationWindows.map { it.values }
        val energias = FloatArray(genuineWindows.size) {
            WindowEnergy.accStdMedio(genuineWindows[it], manifest.nFeatures)
        }
        val impostorWindows = if (emparejarImpostores) {
            backgroundTrainPool.sampleMatched(energias, random)
        } else {
            backgroundTrainPool.sample(genuineWindows.size, random)
        }

        val all = genuineWindows + impostorWindows
        val labels = IntArray(all.size) { if (it < genuineWindows.size) 1 else 0 }
        val scores = modelManager.scoreBatch(all)

        // — Umbral calibrado, sin mirar las etiquetas de este conjunto —
        val calibratedThreshold =
            calibrateThreshold(calibrationWindows, random, emparejarImpostores)
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
        // FAR y FRR van en el log junto al EER a propósito: un EER decente con
        // FRR=1.0 es un sistema que no funciona, y sólo se ve mirando las dos
        // tasas por separado.
        Log.i(
            TAG,
            "val: n=${result.numExamples} auc=${"%.4f".format(result.auc)} " +
                "eer=${"%.4f".format(result.eer)} far=${"%.4f".format(result.far)} " +
                "frr=${"%.4f".format(result.frr)} " +
                "thr=${"%.6f".format(result.calibratedThreshold)} " +
                "(calibrado con ${calibrationWindows.size} genuinas)"
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
        calibrationWindows: List<SensorWindow>,
        random: Random,
        emparejarImpostores: Boolean
    ): Float {
        if (backgroundCalibPool.isEmpty() || calibrationWindows.isEmpty()) {
            Log.w(TAG, "Sin pool de calibración: se usa el umbral por defecto")
            return manifest.decisionThreshold
        }
        val genuineScores = modelManager.scoreBatch(calibrationWindows.map { it.values })
        // También emparejado: si el fondo de calibración se mueve más que las
        // genuinas, el cruce FAR/FRR cae donde no debe y el umbral operativo
        // sale sesgado por la misma razón de dominio.
        val energiasCalib = FloatArray(calibrationWindows.size) {
            WindowEnergy.accStdMedio(calibrationWindows[it].values, manifest.nFeatures)
        }
        val calibWindows = if (emparejarImpostores) {
            backgroundCalibPool.sampleMatched(energiasCalib, random)
        } else {
            backgroundCalibPool.sample(
                minOf(CALIBRATION_POOL_SIZE, backgroundCalibPool.size), random
            )
        }
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
