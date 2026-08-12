package com.example.autenticacioncontinua.ml.model

import android.content.Context
import org.json.JSONObject

/**
 * Contrato entre el `.tflite`, el cliente Kotlin y el servidor Flower.
 *
 * Lo emite `backend/export_tflite_model.py` como `model_manifest.json` y se
 * empaqueta en los assets del APK junto al modelo. Todo lo que ambos extremos
 * tienen que acordar (formas, tamaño del vector de pesos, hiperparámetros de
 * entrenamiento local) vive aquí, de modo que un desajuste se detecta al
 * arrancar y no como un EER inexplicable semanas después.
 */
data class ModelManifest(
    val modelFile: String,

    // — Señal —
    val windowSize: Int,
    val nFeatures: Int,
    val targetHz: Int,
    val windowStep: Int,
    val sensorConfig: String,
    val channelOrder: List<String>,

    // — Pesos —
    /** Tamaño del vector plano del encoder: lo ÚNICO que viaja por FedAvg. */
    val encoderFlatSize: Int,
    /** Tamaño del vector plano de la cabeza: nunca sale del dispositivo. */
    val headFlatSize: Int,

    // — Entrenamiento local —
    val localEpochs: Int,
    val batchSize: Int,
    val trainGenuinePerBatch: Int,
    val trainBackgroundPerBatch: Int,
    val learningRate: Float,
    val clsLossWeight: Float,
    val backgroundRatio: Float,
    val resetOptimizerEachRound: Boolean,

    // — Evaluación —
    val inferBatch: Int,
    val decisionThreshold: Float,
    val testRatio: Float,
    val valRatio: Float,

    // — Assets —
    val scalerStatsFile: String,
    val scalerIsIdentityPlaceholder: Boolean,
    val backgroundTrainFile: String?,
    val backgroundTrainWindows: Int,
    val backgroundCalibFile: String?,
    val backgroundCalibWindows: Int
) {
    /** Número de floats de una ventana aplanada. */
    val windowFloats: Int get() = windowSize * nFeatures

    companion object {
        const val ASSET_NAME = "model_manifest.json"

        fun fromAssets(context: Context, assetName: String = ASSET_NAME): ModelManifest {
            val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
            return parse(JSONObject(raw))
        }

        fun parse(json: JSONObject): ModelManifest {
            val signal = json.getJSONObject("signal")
            val weights = json.getJSONObject("weights")
            val training = json.getJSONObject("training")
            val evaluation = json.getJSONObject("evaluation")
            val assets = json.getJSONObject("assets")

            val channels = signal.getJSONArray("channel_order")
            val channelOrder = (0 until channels.length()).map { channels.getString(it) }

            return ModelManifest(
                modelFile = json.getString("model_file"),

                windowSize = signal.getInt("window_size"),
                nFeatures = signal.getInt("n_features"),
                targetHz = signal.getInt("target_hz"),
                windowStep = signal.getInt("window_step"),
                sensorConfig = signal.getString("sensor_config"),
                channelOrder = channelOrder,

                encoderFlatSize = weights.getInt("encoder_flat_size"),
                headFlatSize = weights.getInt("head_flat_size"),

                localEpochs = training.getInt("local_epochs"),
                batchSize = training.getInt("batch_size"),
                trainGenuinePerBatch = training.getInt("train_genuine_per_batch"),
                trainBackgroundPerBatch = training.getInt("train_background_per_batch"),
                learningRate = training.getDouble("learning_rate").toFloat(),
                clsLossWeight = training.getDouble("cls_loss_weight").toFloat(),
                backgroundRatio = training.getDouble("background_ratio").toFloat(),
                resetOptimizerEachRound = training.optBoolean("reset_optimizer_each_round", true),

                inferBatch = evaluation.getInt("infer_batch"),
                decisionThreshold = evaluation.getDouble("decision_threshold").toFloat(),
                testRatio = evaluation.getDouble("test_ratio").toFloat(),
                valRatio = evaluation.getDouble("val_ratio").toFloat(),

                scalerStatsFile = assets.getString("scaler_stats"),
                scalerIsIdentityPlaceholder =
                    assets.optBoolean("scaler_is_identity_placeholder", false),
                backgroundTrainFile = assets.optStringOrNull("background_train"),
                backgroundTrainWindows = assets.optInt("background_train_windows", 0),
                backgroundCalibFile = assets.optStringOrNull("background_calib"),
                backgroundCalibWindows = assets.optInt("background_calib_windows", 0)
            )
        }

        /** `optString` de org.json convierte JSON `null` en la cadena "null". */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
