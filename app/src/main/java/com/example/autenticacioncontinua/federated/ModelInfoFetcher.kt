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
/**
 * @param ablation modo del experimento que dicta el servidor: `"full"` aplica
 *   el filtro de actividad y empareja los impostores por energía;
 *   `"baseline"` reproduce el comportamiento anterior a la v1.6 (sin filtro,
 *   impostores al azar). Lo decide el servidor para que las dos corridas que
 *   se comparan usen literalmente el mismo APK.
 *
 *   Por defecto `"full"` si el servidor no lo publica: un backend viejo no
 *   debe cambiar en silencio el comportamiento de la app.
 *
 *   NO entra en [requireCompatibleWith]: no es parte del contrato del modelo
 *   y un desajuste aquí no invalida la agregación.
 */
data class ModelInfo(
    val sensorConfig: String,
    val windowSize: Int,
    val encoderFlatSize: Int?,
    val ablation: String = ABLATION_FULL
) {
    /**
     * Las dos correcciones son INDEPENDIENTES y responden a preguntas
     * distintas, por eso se controlan por separado:
     *
     *  - El FILTRO cambia qué ventanas existen, y por tanto la partición y el
     *    conjunto de test. Comparar EER con y sin filtro es comparar exámenes
     *    distintos: no es un A/B, se reporta como par (EER, abstención).
     *  - El EMPAREJADO sólo cambia de dónde salen las impostoras. Con el
     *    filtro fijo en las dos condiciones, las genuinas de test son las
     *    MISMAS y la única variable es esa. Eso sí es un A/B limpio.
     *
     * De ahí el tercer modo `matched_off`: filtro sí, emparejado no.
     */
    val aplicarFiltro: Boolean get() = ablation != ABLATION_BASELINE

    val emparejarImpostores: Boolean
        get() = ablation != ABLATION_BASELINE && ablation != ABLATION_MATCHED_OFF

    companion object {
        const val ABLATION_FULL = "full"
        const val ABLATION_BASELINE = "baseline"
        const val ABLATION_MATCHED_OFF = "matched_off"

        /**
         * Impostores de OTRO PARTICIPANTE REAL, con la misma app y la misma
         * tuberia de captura (pendiente G). Exige que el dispositivo tenga los
         * ficheros del par en filesDir; si no, la sesion aborta en vez de caer
         * en silencio a HMOG.
         *
         * Filtro y emparejado siguen activos: contra un par real el emparejado
         * ya no corrige nada (las energias son comparables por construccion),
         * pero mantenerlo hace que la unica diferencia frente a `full` sea de
         * DONDE salen los impostores.
         */
        const val ABLATION_PEER = "peer"
    }
}

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
                },
                ablation = json.optString("ablation", ModelInfo.ABLATION_FULL)
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
