package com.example.autenticacioncontinua.federated

import com.example.autenticacioncontinua.BuildConfig
import com.example.autenticacioncontinua.ml.model.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Metadatos del modelo global publicados por el backend.
 *
 * @param encoderFlatSize tamaño del vector plano del encoder. Es el único
 *   número que servidor y cliente deben acordar para el intercambio FedAvg;
 *   comprobarlo por REST antes de abrir el canal gRPC evita descubrir el
 *   desajuste a mitad de ronda. Vale `null` mientras el backend siga
 *   publicando el esquema antiguo (se corrige en la Fase 3).
 */
data class ModelInfo(
    val sensorConfig: String,
    val windowSize: Int,
    val encoderFlatSize: Int?
)

class ModelInfoFetcher(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetchModelInfo(): ModelInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://${BuildConfig.SERVER_HOST}/api/model/info")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: model/info falló")
            }
            val body = response.body?.string()
                ?: throw IOException("Respuesta vacía de model/info")

            val json = JSONObject(body)
            ModelInfo(
                sensorConfig = json.getString("sensor_config"),
                windowSize = json.getInt("window_size"),
                encoderFlatSize = if (json.has("encoder_flat_size")) {
                    json.getInt("encoder_flat_size")
                } else {
                    null
                }
            )
        }
    }
}

/**
 * Contrasta los metadatos del servidor con el manifiesto empaquetado.
 *
 * @throws IllegalStateException si no son compatibles. Es preferible fallar
 *   aquí, con un mensaje que dice exactamente qué difiere, a entrenar contra
 *   un modelo global distinto y contaminar la agregación de todos los demás
 *   clientes.
 */
fun ModelInfo.requireCompatibleWith(manifest: ModelManifest) {
    val problems = buildList {
        if (windowSize != manifest.windowSize) {
            add("window_size: servidor=$windowSize, app=${manifest.windowSize}")
        }
        if (sensorConfig != manifest.sensorConfig) {
            add("sensor_config: servidor='$sensorConfig', app='${manifest.sensorConfig}'")
        }
        if (encoderFlatSize != null && encoderFlatSize != manifest.encoderFlatSize) {
            add("encoder_flat_size: servidor=$encoderFlatSize, app=${manifest.encoderFlatSize}")
        }
    }
    check(problems.isEmpty()) {
        "El modelo global del servidor no es compatible con el APK:\n" +
            problems.joinToString("\n") { "  - $it" } +
            "\nEl backend debe migrarse a la arquitectura FedPer (Fase 3) y " +
            "servir el mismo artefacto generado por export_tflite_model.py."
    }
}
