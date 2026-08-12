package com.example.autenticacioncontinua.ml.data

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Pools de ventanas "impostoras" empaquetados en el APK.
 *
 * En la simulación, cada cliente construye su mitad negativa muestreando el
 * pool BACKGROUND de sujetos que nunca se enrolan (mejor.py:474-497). Un móvil
 * real no tiene datos de otras personas, así que el pool viaja con la app.
 *
 * Hay DOS pools y son disjuntos por diseño:
 *
 *  - [trainPool]  (`BACKGROUND_TRAIN_SUBJECT_IDS`): mitad negativa del
 *    entrenamiento local y de la evaluación.
 *  - [calibPool]  (`BACKGROUND_CALIB_SUBJECT_IDS`): SÓLO para calibrar el
 *    umbral de decisión.
 *
 * Mantenerlos separados es lo que evita que el umbral se calibre contra los
 * mismos impostores con los que después se mide FAR/FRR. Es una de las
 * decisiones metodológicas fuertes del cuadernillo y hay que preservarla.
 *
 * Formato del asset: float32 little-endian crudo, orden C,
 * `(N, windowSize, nFeatures)`, YA normalizado con el mismo StandardScaler
 * global. Una ventana ocupa 128*6*4 = 3 072 bytes.
 */
class BackgroundPool private constructor(
    private val data: FloatArray,
    val windowFloats: Int
) {
    val size: Int get() = data.size / windowFloats

    fun isEmpty(): Boolean = size == 0

    /** Copia la ventana `index` a un nuevo array. */
    fun window(index: Int): FloatArray {
        val from = index * windowFloats
        return data.copyOfRange(from, from + windowFloats)
    }

    /**
     * Toma `count` ventanas al azar. Muestrea sin reemplazo mientras el pool
     * dé de sí y con reemplazo por encima de ese punto, igual que hace
     * `partition_fl_binary_auth` cuando la cuota supera al sujeto disponible.
     */
    fun sample(count: Int, random: Random): List<FloatArray> {
        if (count <= 0 || isEmpty()) return emptyList()
        val n = size
        return if (count <= n) {
            val indices = (0 until n).shuffled(random).take(count)
            indices.map { window(it) }
        } else {
            List(count) { window(random.nextInt(n)) }
        }
    }

    companion object {

        /**
         * @param expectedWindows número de ventanas declarado en el manifiesto;
         *   sirve para detectar un asset truncado o desincronizado.
         */
        fun fromAssets(
            context: Context,
            assetName: String,
            windowFloats: Int,
            expectedWindows: Int
        ): BackgroundPool {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val bytesPerWindow = windowFloats * 4

            if (bytes.size % bytesPerWindow != 0) {
                throw IOException(
                    "$assetName mide ${bytes.size} bytes, no es múltiplo de " +
                        "$bytesPerWindow (una ventana). Asset corrupto o generado " +
                        "con otra configuración de ventana."
                )
            }
            val n = bytes.size / bytesPerWindow
            if (expectedWindows > 0 && n != expectedWindows) {
                throw IOException(
                    "$assetName contiene $n ventanas, el manifiesto declara " +
                        "$expectedWindows. Regenera los assets con " +
                        "export_tflite_model.py."
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buffer.asFloatBuffer().get(floats)
            return BackgroundPool(floats, windowFloats)
        }

        fun empty(windowFloats: Int): BackgroundPool =
            BackgroundPool(FloatArray(0), windowFloats)
    }
}
