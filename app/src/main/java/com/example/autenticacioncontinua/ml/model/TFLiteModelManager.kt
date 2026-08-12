package com.example.autenticacioncontinua.ml.model

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Pérdidas devueltas por un paso de entrenamiento. */
data class TrainStepLoss(
    val loss: Float,
    val reconLoss: Float,
    val clsLoss: Float
)

/** Salida de la firma de inferencia para una ventana. */
data class InferenceResult(
    val genuineScore: Float,
    val reconstructionError: Float,
    val isGenuine: Boolean
)

/**
 * Puente entre Kotlin y las firmas del `.tflite` FedPer.
 *
 * El modelo expone dos bloques de pesos independientes:
 *
 *  - **encoder**: `save_encoder` / `restore_encoder`. Es lo único que viaja
 *    por FedAvg. Se serializa como un único vector plano `float32` de
 *    [ModelManifest.encoderFlatSize] elementos, con los offsets horneados en
 *    el grafo. Un solo número que acordar entre Python y Kotlin, en lugar de
 *    quince tensores de formas distintas cuyo emparejamiento dependería del
 *    orden alfabético de los nombres que TFLite asigne a la firma.
 *  - **cabeza**: `save_head` / `restore_head`. Nunca sale del dispositivo;
 *    se persiste localmente con [HeadStore].
 *
 * Todas las firmas tienen formas FIJAS. La API de SignatureRunner de
 * TFLite/Java no redimensiona entradas dinámicas de forma fiable, y los
 * cambios de forma en tiempo de ejecución son la causa habitual de los
 * crashes de entrenamiento on-device. El precio es rellenar el último lote
 * incompleto, que se hace en [scoreBatch].
 *
 * El intérprete NO es thread-safe: todo acceso se serializa con [lock].
 */
class TFLiteModelManager(
    context: Context,
    val manifest: ModelManifest
) {
    private val lock = Any()
    private var flexDelegate: FlexDelegate? = null
    private var interpreter: Interpreter? = null

    private val windowFloats = manifest.windowFloats

    /** Ventana de ceros reutilizada para rellenar el último lote de inferencia. */
    private val paddingWindow = FloatArray(windowFloats)

    init {
        val delegate = FlexDelegate()
        flexDelegate = delegate
        val options = Interpreter.Options().apply {
            setUseNNAPI(false) // incompatible con entrenamiento on-device
            setNumThreads(NUM_THREADS)
            addDelegate(delegate) // SELECT_TF_OPS: LSTM entrenable, dropout, variables
        }
        val model = loadModelFile(context, manifest.modelFile)
        interpreter = Interpreter(model, options).apply { allocateTensors() }
        verifyContract()
        runSignature(SIG_INITIALIZE, dummyInput(), mutableMapOf("status" to intBuffer(1)))
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    private fun loadModelFile(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }

    /**
     * Comprueba, contra el propio `.tflite`, que las formas coinciden con el
     * manifiesto.
     *
     * Detecta al arrancar el desajuste que de otro modo aparecería como una
     * excepción opaca en mitad de una ronda federada o, peor, como pesos
     * reinterpretados en silencio con la longitud equivocada.
     */
    private fun verifyContract() {
        val interp = requireInterpreter()
        val signatures = interp.signatureKeys.toSet()
        val required = listOf(
            SIG_INITIALIZE, SIG_TRAIN_STEP, SIG_INFER, SIG_INFER_BATCH,
            SIG_SAVE_ENCODER, SIG_RESTORE_ENCODER,
            SIG_SAVE_HEAD, SIG_RESTORE_HEAD,
            SIG_SET_LR, SIG_RESET_OPTIMIZER
        )
        val missing = required.filterNot { it in signatures }
        require(missing.isEmpty()) {
            "El modelo ${manifest.modelFile} no expone las firmas $missing. " +
                "Presentes: ${signatures.sorted()}. Regenera el artefacto con " +
                "backend/export_tflite_model.py."
        }

        val encoderLen = interp
            .getInputTensorFromSignature("encoder_flat", SIG_RESTORE_ENCODER)
            .shape().single()
        require(encoderLen == manifest.encoderFlatSize) {
            "encoder_flat_size del manifiesto (${manifest.encoderFlatSize}) no " +
                "coincide con el del modelo ($encoderLen). El servidor Flower " +
                "enviaría vectores de longitud equivocada."
        }

        val headLen = interp
            .getInputTensorFromSignature("head_flat", SIG_RESTORE_HEAD)
            .shape().single()
        require(headLen == manifest.headFlatSize) {
            "head_flat_size del manifiesto (${manifest.headFlatSize}) no coincide " +
                "con el del modelo ($headLen)."
        }

        val trainShape = interp
            .getInputTensorFromSignature("x_genuine", SIG_TRAIN_STEP).shape()
        require(
            trainShape.size == 3 &&
                trainShape[0] == manifest.trainGenuinePerBatch &&
                trainShape[1] == manifest.windowSize &&
                trainShape[2] == manifest.nFeatures
        ) {
            "train_step.x_genuine tiene forma ${trainShape.toList()}, el manifiesto " +
                "describe [${manifest.trainGenuinePerBatch}, ${manifest.windowSize}, " +
                "${manifest.nFeatures}]."
        }

        Log.i(TAG, "Contrato verificado: encoder=$encoderLen floats, head=$headLen floats")
    }

    fun close() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
            flexDelegate?.close()
            flexDelegate = null
        }
    }

    private fun requireInterpreter(): Interpreter =
        interpreter ?: throw IllegalStateException("El intérprete TFLite está cerrado")

    // ------------------------------------------------------------------
    // Pesos
    // ------------------------------------------------------------------

    /** Vector plano del encoder: lo que se envía al servidor tras `fit`. */
    fun getEncoderWeights(): FloatArray =
        readFlat(SIG_SAVE_ENCODER, "encoder_flat", manifest.encoderFlatSize)

    /** Inyecta el encoder agregado que llega del servidor. */
    fun setEncoderWeights(weights: FloatArray) =
        writeFlat(SIG_RESTORE_ENCODER, "encoder_flat", weights, manifest.encoderFlatSize)

    fun getHeadWeights(): FloatArray =
        readFlat(SIG_SAVE_HEAD, "head_flat", manifest.headFlatSize)

    fun setHeadWeights(weights: FloatArray) =
        writeFlat(SIG_RESTORE_HEAD, "head_flat", weights, manifest.headFlatSize)

    private fun readFlat(signature: String, outputName: String, size: Int): FloatArray {
        val buffer = floatBuffer(size)
        runSignature(signature, dummyInput(), mutableMapOf(outputName to buffer))
        val out = FloatArray(size)
        buffer.rewound().asFloatBuffer().get(out)
        return out
    }

    private fun writeFlat(
        signature: String,
        inputName: String,
        weights: FloatArray,
        expectedSize: Int
    ) {
        require(weights.size == expectedSize) {
            "Se recibieron ${weights.size} floats para '$inputName', se esperaban " +
                "$expectedSize. ¿Servidor y cliente comparten el mismo artefacto?"
        }
        val buffer = floatBuffer(expectedSize)
        buffer.asFloatBuffer().put(weights)
        runSignature(
            signature,
            mapOf(inputName to buffer),
            mutableMapOf("status" to intBuffer(1))
        )
    }

    // ------------------------------------------------------------------
    // Entrenamiento
    // ------------------------------------------------------------------

    /**
     * Un paso de gradiente sobre un lote balanceado.
     *
     * Reproduce la pérdida multi-tarea de `AuthClient.fit`: MSE de
     * reconstrucción ponderado por la etiqueta (sólo los genuinos enseñan al
     * autoencoder) más `cls_loss_weight` veces la entropía cruzada binaria,
     * más la regularización L2.
     *
     * @param genuine exactamente [ModelManifest.trainGenuinePerBatch] ventanas.
     * @param background exactamente [ModelManifest.trainBackgroundPerBatch].
     */
    fun trainStep(genuine: List<FloatArray>, background: List<FloatArray>): TrainStepLoss {
        require(genuine.size == manifest.trainGenuinePerBatch) {
            "trainStep espera ${manifest.trainGenuinePerBatch} ventanas genuinas, " +
                "recibió ${genuine.size}"
        }
        require(background.size == manifest.trainBackgroundPerBatch) {
            "trainStep espera ${manifest.trainBackgroundPerBatch} ventanas de " +
                "background, recibió ${background.size}"
        }

        val loss = floatBuffer(1)
        val recon = floatBuffer(1)
        val cls = floatBuffer(1)
        runSignature(
            SIG_TRAIN_STEP,
            mapOf(
                "x_genuine" to windowsBuffer(genuine),
                "x_background" to windowsBuffer(background)
            ),
            mutableMapOf("loss" to loss, "recon_loss" to recon, "cls_loss" to cls)
        )
        return TrainStepLoss(
            loss = loss.getFloat(0),
            reconLoss = recon.getFloat(0),
            clsLoss = cls.getFloat(0)
        )
    }

    /**
     * Fija el learning rate de la ronda.
     *
     * `fit_config` de `mejor.py` lo decae a partir de la ronda
     * `0.7 * num_rounds` y lo envía en la configuración de la ronda;
     * `AuthClient.fit` lo aplica con este mismo `assign`.
     */
    fun setLearningRate(lr: Float) {
        val input = floatBuffer(1).apply { putFloat(0, lr) }
        runSignature(
            SIG_SET_LR,
            mapOf("lr" to input),
            mutableMapOf("lr" to floatBuffer(1))
        )
    }

    /**
     * Reinicia los momentos de Adam.
     *
     * En la simulación, Ray reconstruye `AuthClient` (y con él el optimizador)
     * en cada ronda, así que Adam nunca arrastra estado entre rondas. El
     * intérprete on-device sí es persistente, de modo que hay que reiniciarlo
     * explícitamente al empezar cada `fit` para reproducir esa dinámica.
     */
    fun resetOptimizer() {
        runSignature(SIG_RESET_OPTIMIZER, dummyInput(), mutableMapOf("status" to intBuffer(1)))
    }

    // ------------------------------------------------------------------
    // Inferencia
    // ------------------------------------------------------------------

    /** Autenticación en tiempo real de una única ventana. */
    fun score(
        window: FloatArray,
        threshold: Float = manifest.decisionThreshold
    ): InferenceResult {
        require(window.size == windowFloats) {
            "Ventana de ${window.size} valores, se esperaban $windowFloats"
        }
        val score = floatBuffer(1)
        val recon = floatBuffer(1)
        val isGenuine = intBuffer(1)
        runSignature(
            SIG_INFER,
            mapOf(
                "x" to windowsBuffer(listOf(window)),
                "threshold" to floatBuffer(1).apply { putFloat(0, threshold) }
            ),
            mutableMapOf(
                "genuine_score" to score,
                "reconstruction_error" to recon,
                "is_genuine" to isGenuine
            )
        )
        return InferenceResult(
            genuineScore = score.getFloat(0),
            reconstructionError = recon.getFloat(0),
            isGenuine = isGenuine.getInt(0) == 1
        )
    }

    /**
     * Puntúa muchas ventanas usando la firma de lote fijo.
     *
     * El último lote se rellena con ceros y las posiciones sobrantes se
     * descartan: es el coste de haber fijado las formas.
     */
    fun scoreBatch(
        windows: List<FloatArray>,
        threshold: Float = manifest.decisionThreshold
    ): FloatArray {
        if (windows.isEmpty()) return FloatArray(0)
        val batch = manifest.inferBatch
        val out = FloatArray(windows.size)
        val thresholdBuffer = floatBuffer(1).apply { putFloat(0, threshold) }
        val decoded = FloatArray(batch)

        var offset = 0
        while (offset < windows.size) {
            val take = minOf(batch, windows.size - offset)
            val chunk = ArrayList<FloatArray>(batch)
            for (i in 0 until take) chunk.add(windows[offset + i])
            repeat(batch - take) { chunk.add(paddingWindow) }

            val scores = floatBuffer(batch)
            runSignature(
                SIG_INFER_BATCH,
                mapOf("x" to windowsBuffer(chunk), "threshold" to thresholdBuffer),
                mutableMapOf(
                    "genuine_score" to scores,
                    "reconstruction_error" to floatBuffer(batch),
                    "is_genuine" to intBuffer(batch)
                )
            )
            scores.rewound().asFloatBuffer().get(decoded)
            System.arraycopy(decoded, 0, out, offset, take)
            offset += take
        }
        return out
    }

    // ------------------------------------------------------------------
    // Utilidades de buffers
    // ------------------------------------------------------------------

    private fun runSignature(
        key: String,
        inputs: Map<String, Any>,
        outputs: MutableMap<String, Any>
    ) {
        synchronized(lock) {
            val interp = requireInterpreter()
            if (key in SIGNATURES_CON_LSTM) {
                // El LSTM del encoder se convierte a UNIDIRECTIONAL_SEQUENCE_LSTM,
                // que CONSERVA su estado oculto y de celda entre invocaciones.
                // Sin este reset, la misma ventana puntúa distinto según cuántas
                // inferencias la precedieron: medido 0.6220 -> 0.5312 en seis
                // llamadas idénticas, y hasta 0.51 de diferencia entre `infer` e
                // `infer_batch`. El SavedModel sin convertir no tiene el problema,
                // así que lo introduce el convertidor.
                //
                // Los pesos NO se ven afectados: viven en variables de recurso
                // (VAR_HANDLE/READ_VARIABLE), no en tensores-variable de TFLite.
                // Comprobado: encoder y cabeza quedan intactos tras el reset, y
                // las 32 ventanas de un lote pasan a coincidir exactamente con
                // la inferencia individual.
                interp.resetVariableTensors()
            }
            interp.runSignature(inputs, outputs, key)
        }
    }

    /**
     * Entrada para las firmas que no necesitan ninguna.
     *
     * `Interpreter.runSignature` lanza `IllegalArgumentException("Input error:
     * Inputs should not be null or empty.")` si el mapa de entradas viene
     * vacío, así que `initialize`, `reset_optimizer`, `save_encoder` y
     * `save_head` se exportan con un escalar que el grafo descarta. El
     * intérprete de Python no tiene esa restricción: por eso
     * `verify_tflite_model.py` pasaba mientras el dispositivo fallaba.
     */
    private fun dummyInput(): Map<String, Any> =
        mapOf("dummy" to floatBuffer(1))

    private fun windowsBuffer(windows: List<FloatArray>): ByteBuffer {
        val buffer = floatBuffer(windows.size * windowFloats)
        val view = buffer.asFloatBuffer()
        for (w in windows) {
            require(w.size == windowFloats) {
                "Ventana de ${w.size} valores, se esperaban $windowFloats"
            }
            view.put(w)
        }
        return buffer
    }

    private fun floatBuffer(elements: Int): ByteBuffer =
        ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())

    private fun intBuffer(elements: Int): ByteBuffer =
        ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())

    /**
     * `ByteBuffer.rewind()` devuelve `Buffer` en el SDK de Android, no
     * `ByteBuffer` como en Java 9+. El cast explícito evita el
     * `NoSuchMethodError` que aparecería sólo en tiempo de ejecución.
     */
    private fun ByteBuffer.rewound(): ByteBuffer {
        (this as Buffer).rewind()
        return this
    }

    companion object {
        private const val TAG = "TFLiteModelManager"
        private const val NUM_THREADS = 2

        const val SIG_INITIALIZE = "initialize"
        const val SIG_TRAIN_STEP = "train_step"
        const val SIG_INFER = "infer"
        const val SIG_INFER_BATCH = "infer_batch"
        const val SIG_SAVE_ENCODER = "save_encoder"
        const val SIG_RESTORE_ENCODER = "restore_encoder"
        const val SIG_SAVE_HEAD = "save_head"
        const val SIG_RESTORE_HEAD = "restore_head"
        const val SIG_SET_LR = "set_lr"
        const val SIG_RESET_OPTIMIZER = "reset_optimizer"

        /**
         * Firmas que hacen pasar datos por la red y, por tanto, por el LSTM.
         * Ver [runSignature].
         *
         * Vive en el companion a propósito: como propiedad de instancia se
         * inicializaría DESPUÉS del bloque `init`, que ya invoca `initialize`,
         * y daría NullPointerException al arrancar.
         */
        private val SIGNATURES_CON_LSTM =
            setOf(SIG_TRAIN_STEP, SIG_INFER, SIG_INFER_BATCH)
    }
}
