package com.example.autenticacioncontinua.ml.data

import android.content.Context
import android.util.Log
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
/**
 * @param fuente de dónde salieron estas ventanas. Se propaga para que el
 *   resultado pueda etiquetarse: un EER medido contra HMOG y otro medido
 *   contra un usuario real NO son el mismo número, y confundirlos sería el
 *   peor error posible en este proyecto.
 */
class BackgroundPool private constructor(
    private val data: FloatArray,
    val windowFloats: Int,
    private val nFeatures: Int,
    val fuente: String = FUENTE_HMOG
) {
    val size: Int get() = data.size / windowFloats

    /**
     * Energía de cada ventana del pool, para [sampleMatched].
     *
     * Se calcula una sola vez y de forma perezosa: son 600 ventanas de 128x6,
     * unos 460 mil flotantes. Hacerlo en cada ronda federada sería tirar
     * batería —que es justo lo que este proyecto mide— por recalcular algo
     * inmutable.
     */
    private val energias: FloatArray? by lazy {
        if (isEmpty() || nFeatures <= 0) null
        else FloatArray(size) { WindowEnergy.accStdMedio(window(it), nFeatures) }
    }

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

    /**
     * Muestrea impostoras con energía PAREJA a la de cada ventana genuina.
     *
     * POR QUÉ. Los impostores vienen de HMOG, que se recogió con otros
     * terminales y —esto es lo decisivo— con un protocolo que incluía sesiones
     * CAMINANDO (Sitová et al., 2016, reportan EER por separado para "sentado"
     * y "caminando"). Caminar genera mucha más energía inercial que el uso
     * ambiental que recoge esta app. Resultado medido sobre datos reales: un
     * ÚNICO escalar —la media de desviaciones típicas por eje del
     * acelerómetro— separa nuestras ventanas de las de HMOG con AUC 0.72 en un
     * terminal y 0.81 en otro. Es decir, parte de lo que el modelo puede
     * aprender es "cuánto se mueve el teléfono", no quién lo usa.
     *
     * QUÉ HACE ESTO. Equilibrar la variable de estorbo entre clases (*matched
     * sampling* / balanceo de covariable): si la energía está repartida igual
     * en genuinas e impostoras, el clasificador no puede usarla para separar.
     * Verificado sobre los datos del estudio:
     *
     *     sin emparejar : AUC 0.7243 (acc)   0.7169 (gyro)
     *     emparejado    : AUC 0.5007 (acc)   0.5076 (gyro)
     *
     * LO QUE NO ARREGLA: la brecha de dominio completa. Sólo neutraliza el eje
     * de energía, que es el que sabemos medir. Impostores REALES con la misma
     * app siguen siendo el arreglo de fondo (pendiente G).
     *
     * Se muestrea CON reemplazo a propósito: exigir impostoras distintas
     * dejaría fuera a las genuinas cuya energía es rara, y perder genuinas
     * sesga el conjunto justo por el extremo que interesa conservar. El precio
     * es menos diversidad de impostores, y hay que decirlo al reportar.
     *
     * @param energiasGenuinas energía de cada ventana genuina a emparejar.
     * @param tolerancia fracción de desviación admitida (0.10 = ±10%).
     * @return una impostora por cada energía de entrada. Cuando ninguna cae
     *   dentro de la tolerancia se devuelve la MÁS PRÓXIMA, para no romper el
     *   balance de clases que espera quien llama.
     */
    fun sampleMatched(
        energiasGenuinas: FloatArray,
        random: Random,
        tolerancia: Float = DEFAULT_TOLERANCIA
    ): List<FloatArray> {
        if (energiasGenuinas.isEmpty() || isEmpty()) return emptyList()
        val propias = energias ?: return sample(energiasGenuinas.size, random)

        return energiasGenuinas.map { objetivo ->
            val margen = tolerancia * maxOf(objetivo, 1e-9f)
            val candidatos = propias.indices.filter {
                kotlin.math.abs(propias[it] - objetivo) <= margen
            }
            val elegido = if (candidatos.isNotEmpty()) {
                candidatos[random.nextInt(candidatos.size)]
            } else {
                propias.indices.minByOrNull {
                    kotlin.math.abs(propias[it] - objetivo)
                } ?: 0
            }
            window(elegido)
        }
    }

    companion object {

        const val FUENTE_HMOG = "hmog"
        const val FUENTE_PAR = "par_real"

        /**
         * Carga el pool desde un fichero del almacenamiento privado si existe,
         * y si no desde los assets del APK.
         *
         * ES LA VIA DEL ESTUDIO DE VALIDACION CRUZADA (pendiente G). Los
         * ficheros del par se empujan a mano por adb y NO viajan dentro del
         * APK: el sistema desplegado sigue sin que los datos crudos de un
         * usuario salgan de su dispositivo. Que la ruta exista no significa que
         * el producto comparta datos; significa que el investigador puede
         * colocar un conjunto de impostores concreto para UNA medicion.
         *
         * El fichero manda sobre el asset a proposito, y se registra en el log
         * con letra bien grande: quien lea el resultado tiene que poder saber
         * contra que se midio.
         */
        fun fromFileOrAssets(
            context: Context,
            assetName: String,
            overrideFileName: String,
            windowFloats: Int,
            expectedWindows: Int,
            nFeatures: Int
        ): BackgroundPool {
            val f = java.io.File(context.filesDir, overrideFileName)
            if (!f.exists()) {
                return fromAssets(context, assetName, windowFloats, expectedWindows, nFeatures)
            }
            val bytes = f.readBytes()
            val bytesPerWindow = windowFloats * 4
            if (bytes.size % bytesPerWindow != 0 || bytes.isEmpty()) {
                throw IOException(
                    "${f.name} mide ${bytes.size} bytes, no es multiplo de " +
                        "$bytesPerWindow. Regeneralo con generar_pool_par.py."
                )
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buffer.asFloatBuffer().get(floats)
            Log.w(
                "BackgroundPool",
                "POOL DE PAR REAL en uso: ${f.name} con " +
                    "${bytes.size / bytesPerWindow} ventanas (sustituye a $assetName). " +
                    "El resultado NO es comparable con una medicion contra HMOG."
            )
            return BackgroundPool(floats, windowFloats, nFeatures, FUENTE_PAR)
        }

        /**
         * Tolerancia por defecto del emparejado, ±10%.
         *
         * Medida sobre los datos del estudio: con ±10% encuentran pareja 650 de
         * 800 ventanas genuinas (81%) y el AUC del confound de energía baja de
         * 0.7243 a 0.5007. Ampliarla a ±35% sólo sube la cobertura al 86% y
         * empeora el balance, porque admite parejas más desiguales.
         */
        const val DEFAULT_TOLERANCIA = 0.10f

        /**
         * @param expectedWindows número de ventanas declarado en el manifiesto;
         *   sirve para detectar un asset truncado o desincronizado.
         */
        fun fromAssets(
            context: Context,
            assetName: String,
            windowFloats: Int,
            expectedWindows: Int,
            nFeatures: Int
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
            return BackgroundPool(floats, windowFloats, nFeatures, FUENTE_HMOG)
        }

        fun empty(windowFloats: Int): BackgroundPool =
            BackgroundPool(FloatArray(0), windowFloats, 0, FUENTE_HMOG)

        /**
         * Construye un pool desde ventanas en memoria.
         *
         * Existe para los tests de JVM: [fromAssets] necesita un `Context` de
         * Android y no se puede usar fuera del dispositivo. Se prefiere esto a
         * relajar la visibilidad del constructor.
         */
        @JvmStatic
        fun fromFloatsForTest(
            ventanas: List<FloatArray>,
            windowFloats: Int,
            nFeatures: Int
        ): BackgroundPool {
            val plano = FloatArray(ventanas.size * windowFloats)
            ventanas.forEachIndexed { i, v ->
                v.copyInto(plano, i * windowFloats)
            }
            return BackgroundPool(plano, windowFloats, nFeatures)
        }
    }
}
