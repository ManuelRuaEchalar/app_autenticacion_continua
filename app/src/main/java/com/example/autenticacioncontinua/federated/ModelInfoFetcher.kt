package com.example.autenticacioncontinua.federated

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import com.example.autenticacioncontinua.BuildConfig

data class ModelInfo(
    val sensorConfig: String,
    val windowSize: Int
)

class ModelInfoFetcher(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetchModelInfo(): ModelInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://${BuildConfig.SERVER_HOST}/api/model/info")
            .build()
            
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: model/info falló")
        val body = response.body?.string() ?: throw IOException("Empty response from model/info")
            
        val json = JSONObject(body)
        ModelInfo(
            sensorConfig = json.getString("sensor_config"),
            windowSize = json.getInt("window_size"),
        )
    }
}
