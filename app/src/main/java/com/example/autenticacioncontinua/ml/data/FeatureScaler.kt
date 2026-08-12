package com.example.autenticacioncontinua.ml.data

import android.content.Context
import org.json.JSONObject

/**
 * StandardScaler global de la simulación, aplicado on-device.
 *
 * `mejor.py` ajusta un único `StandardScaler` con las ventanas de
 * entrenamiento de TODOS los clientes y lo aplica por igual a train y test
 * (mejor.py:516-525). El encoder federado sólo tiene sentido sobre entradas
 * en esa misma escala.
 *
 * Sin este paso el modelo recibiría lecturas crudas del sensor (m/s^2 y
 * rad/s, con medias muy alejadas de cero) en lugar de valores tipificados.
 * No fallaría nada: simplemente devolvería puntuaciones sin sentido. Por eso
 * el manifiesto marca explícitamente cuándo el scaler es un marcador de
 * posición identidad.
 *
 * En producción se distribuye el scaler derivado de HMOG, no uno calculado
 * con datos del usuario: calcularlo por dispositivo haría que cada cliente
 * enviase gradientes en un espacio de entrada distinto y FedAvg promediaría
 * encoders incompatibles.
 */
class FeatureScaler(
    private val mean: FloatArray,
    private val scale: FloatArray,
    val isIdentityPlaceholder: Boolean
) {
    val nFeatures: Int get() = mean.size

    /**
     * Normaliza en el sitio una ventana aplanada de `windowSize * nFeatures`
     * en orden C.
     */
    fun transformInPlace(window: FloatArray) {
        val n = nFeatures
        require(window.size % n == 0) {
            "Ventana de ${window.size} valores, no es múltiplo de $n canales"
        }
        var i = 0
        while (i < window.size) {
            var c = 0
            while (c < n) {
                window[i + c] = (window[i + c] - mean[c]) / scale[c]
                c++
            }
            i += n
        }
    }

    companion object {
        const val DEFAULT_ASSET = "scaler_stats.json"

        fun fromAssets(
            context: Context,
            assetName: String = DEFAULT_ASSET,
            expectedFeatures: Int
        ): FeatureScaler {
            val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val json = JSONObject(raw)

            val meanArr = json.getJSONArray("mean")
            val scaleArr = json.getJSONArray("scale")
            require(meanArr.length() == expectedFeatures && scaleArr.length() == expectedFeatures) {
                "$assetName describe ${meanArr.length()} canales, el modelo espera $expectedFeatures"
            }

            val mean = FloatArray(expectedFeatures) { meanArr.getDouble(it).toFloat() }
            val scale = FloatArray(expectedFeatures) {
                val v = scaleArr.getDouble(it).toFloat()
                // StandardScaler de sklearn ya sustituye la escala 0 por 1,
                // pero un canal constante en el export volvería a colarla.
                if (v == 0f || !v.isFinite()) 1f else v
            }
            return FeatureScaler(
                mean = mean,
                scale = scale,
                isIdentityPlaceholder = json.optBoolean("is_identity_placeholder", false)
            )
        }
    }
}
