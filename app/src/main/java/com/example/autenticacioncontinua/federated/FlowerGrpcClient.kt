package com.example.autenticacioncontinua.federated

import android.content.Context
import android.util.Log
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.repository.IResourceMeasurementRepository
import com.example.autenticacioncontinua.ml.data.ClientIdentity
import com.example.autenticacioncontinua.ml.data.DatasetSplitter
import com.example.autenticacioncontinua.ml.data.LocalDataset
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.training.EvaluationResult
import com.example.autenticacioncontinua.ml.training.LocalEvaluator
import com.example.autenticacioncontinua.ml.training.LocalTrainer
import com.example.autenticacioncontinua.monitoring.IBatteryMonitor
import com.example.autenticacioncontinua.monitoring.IRamMonitor
import com.google.protobuf.ByteString
import flwr.android_client.ClientMessage
import flwr.android_client.DisconnectRes
import flwr.android_client.EvaluateIns
import flwr.android_client.EvaluateRes
import flwr.android_client.FitIns
import flwr.android_client.FitRes
import flwr.android_client.FlowerServiceGrpcKt
import flwr.android_client.GetParametersRes
import flwr.android_client.Parameters
import flwr.android_client.Reason
import flwr.android_client.Scalar
import flwr.android_client.ServerMessage
import flwr.android_client.Status
import io.grpc.ManagedChannel
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * Progreso de una ronda, para la UI.
 */
data class RoundProgress(
    val round: Int,
    val auc: Double,
    val eer: Double,
    val threshold: Float
)

/**
 * Cliente Flower para el esquema FedPer.
 *
 * Flower es únicamente la capa de orquestación: decide qué clientes
 * participan, mueve arrays de un lado a otro y agrega con FedAvg. No sabe qué
 * es un modelo ni tiene opinión sobre qué parte de él viaja. Que sólo se
 * envíe el encoder es una decisión que se materializa aquí, en
 * [handleFit]: se recibe el vector del encoder, se entrena el modelo completo
 * on-device y se devuelve otra vez sólo el encoder. La cabeza personal se
 * queda en [com.example.autenticacioncontinua.ml.model.HeadStore].
 *
 *
 *
 *
 * El dataset local se construye UNA vez por sesión federada y se reutiliza en
 * todas las rondas: la partición train/val/test tiene que ser idéntica de una
 * ronda a otra o el conjunto ciego dejaría de serlo.
 */
class FlowerGrpcClient(
    private val context: Context,
    private val modelManager: TFLiteModelManager,
    private val localTrainer: LocalTrainer,
    private val localEvaluator: LocalEvaluator,
    private val windowSegmenter: IWindowSegmenter,
    private val clientIdentity: ClientIdentity,
    private val batteryMonitor: IBatteryMonitor,
    private val ramMonitor: IRamMonitor,
    private val metricsRepository: IResourceMeasurementRepository
) {
    private var channel: ManagedChannel? = null
    private var dataset: LocalDataset? = null
    private val manifest = modelManager.manifest

    fun connect(host: String, port: Int = 8080) {
        channel = AndroidChannelBuilder
            .forAddress(host, port)
            .context(context)
            .maxInboundMessageSize(MAX_MESSAGE_BYTES)
            .usePlaintext()
            .build()
    }

    /**
     * Prepara la partición local.
     *
     * @return el dataset, o `null` si no hay material suficiente para
     *   entrenar y validar.
     */
    suspend fun prepareDataset(): LocalDataset? {
        val windows = windowSegmenter.getWindows()
        if (windows.size < MIN_WINDOWS_TO_TRAIN) {
            Log.w(TAG, "Sólo ${windows.size} ventanas; se necesitan $MIN_WINDOWS_TO_TRAIN")
            return null
        }
        val split = DatasetSplitter.split(
            windows = windows,
            testRatio = manifest.testRatio,
            valRatio = manifest.valRatio,
            seed = clientIdentity.splitSeed
        )
        Log.i(TAG, "Partición local -> ${split.summary()}")
        // Una partición formalmente válida puede seguir siendo inservible para
        // reportar: con una decena de ventanas, el EER se mueve en saltos de
        // varios puntos y la curva ROC no tiene resolución. Se avisa aquí para
        // que conste en el logcat junto al número, y no se descubra al
        // escribir la memoria.
        if (split.test.size < MIN_WINDOWS_FOR_STABLE_METRIC) {
            Log.w(
                TAG,
                "El conjunto de test tiene sólo ${split.test.size} ventanas: la " +
                    "medición final NO será estadísticamente informativa " +
                    "(mínimo recomendado $MIN_WINDOWS_FOR_STABLE_METRIC)."
            )
        }
        if (split.validation.size < MIN_WINDOWS_FOR_STABLE_METRIC) {
            Log.w(
                TAG,
                "Validación con sólo ${split.validation.size} ventanas: el " +
                    "val_auc que guíe la parada temprana será muy ruidoso."
            )
        }
        if (!split.isUsable) {
            Log.w(TAG, "Partición inutilizable: faltan sesiones distintas")
            return null
        }
        // Un lote de entrenamiento es de tamaño fijo; por debajo de eso no hay
        // nada que entrenar aunque el split sea formalmente válido.
        if (split.train.size < manifest.trainGenuinePerBatch) {
            Log.w(
                TAG,
                "Sólo ${split.train.size} ventanas de entrenamiento; se necesitan " +
                    "${manifest.trainGenuinePerBatch} para completar un lote"
            )
            return null
        }
        dataset = split
        return split
    }

    /**
     * MEDICIÓN FINAL sobre el conjunto de test. Es el número que va a la tesis.
     *
     * Se ejecuta UNA SOLA VEZ, al terminar la federación, y es la única vez en
     * todo el ciclo que se toca `dataset.test`. Durante las rondas, `evaluate`
     * usa `dataset.validation`: si el test se hubiera mirado antes, habría
     * influido en el early stopping y dejaría de ser un conjunto ciego, que es
     * exactamente el sesgo que `mejor.py` tenía y que esta refactorización
     * corrige (ver REFACTOR_FASE1_FASE2.md, sección "Corrección del sesgo").
     *
     * Se evalúa con el encoder que el dispositivo tiene al cerrar la sesión —el
     * último que envió el servidor— y con la cabeza personal persistida.
     *
     * @return el resultado, o `null` si la partición de test quedó vacía.
     */
    suspend fun evaluateHeldOutTest(): EvaluationResult? {
        val local = dataset
            ?: throw IllegalStateException("prepareDataset() no se ha llamado")
        if (local.test.isEmpty()) {
            Log.w(TAG, "El conjunto de test está vacío; no hay medición final")
            return null
        }

        val tag = "fl_test_final"
        batteryMonitor.startMeasurement(tag)
        ramMonitor.startMeasurement(tag)
        val startedAt = System.currentTimeMillis()

        val result = localEvaluator.evaluate(
            globalEncoder = modelManager.getEncoderWeights(),
            evaluationWindows = local.test,
            genuineTrainWindows = local.train,
            // Semilla fija y distinta de las de ronda: la medición final debe
            // ser reproducible al repetirla sobre el mismo dispositivo.
            seed = clientIdentity.splitSeed + TEST_SEED_OFFSET
        )

        val durationMs = System.currentTimeMillis() - startedAt
        recordResourceUsage(tag, "final_test", durationMs, eer = result.eer)

        Log.i(
            TAG,
            "MEDICION FINAL (test ciego): n=${result.numExamples} " +
                "auc=${result.auc} eer=${result.eer} far=${result.far} " +
                "frr=${result.frr} acc=${result.accuracy} " +
                "umbral=${result.calibratedThreshold} en ${durationMs}ms"
        )
        return result
    }

    suspend fun runFederatedSession(
        onRoundComplete: (RoundProgress) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val activeChannel = channel
            ?: throw IllegalStateException("connect() no se ha llamado")
        val local = dataset
            ?: throw IllegalStateException("prepareDataset() no se ha llamado")

        val stub = FlowerServiceGrpcKt.FlowerServiceCoroutineStub(activeChannel)
        val clientMessages = Channel<ClientMessage>(Channel.UNLIMITED)

        onStatusUpdate("Conectado. Esperando instrucciones del servidor...")
        val responseFlow = stub.join(clientMessages.receiveAsFlow())

        var round = 0
        // El servidor cierra el gRPC justo después de despedirse, y eso hace
        // que `collect` termine con excepción. Sin distinguir ese caso, la
        // terminación NORMAL de la federación parecía un fallo. Ver el `catch`
        // de más abajo.
        var despedidaRecibida = false

        try {
            responseFlow.collect { serverMessage ->
                Log.d(TAG, "Mensaje del servidor: ${serverMessage.msgCase}")
                when (serverMessage.msgCase) {
                    ServerMessage.MsgCase.GET_PARAMETERS_INS -> {
                        onStatusUpdate("Enviando encoder local al servidor...")
                        clientMessages.send(handleGetParameters())
                    }

                    ServerMessage.MsgCase.FIT_INS -> {
                        round++
                        onStatusUpdate(
                            "Ronda $round: entrenando con ${local.train.size} ventanas locales..."
                        )
                        clientMessages.send(handleFit(serverMessage.fitIns, local, round))
                    }

                    ServerMessage.MsgCase.EVALUATE_INS -> {
                        onStatusUpdate("Ronda $round: evaluando sobre validación...")
                        val (message, progress) =
                            handleEvaluate(serverMessage.evaluateIns, local, round)
                        clientMessages.send(message)
                        onRoundComplete(progress)
                    }

                    ServerMessage.MsgCase.RECONNECT_INS -> {
                        despedidaRecibida = true
                        onStatusUpdate("Sesión federada completada.")
                        clientMessages.send(
                            ClientMessage.newBuilder().setDisconnectRes(
                                DisconnectRes.newBuilder()
                                    .setReason(Reason.ACK).build()
                            ).build()
                        )
                        clientMessages.close()
                    }

                    else -> {
                        Log.e(TAG, "Mensaje no soportado: ${serverMessage.msgCase}")
                        onStatusUpdate("Mensaje no soportado del servidor (${serverMessage.msgCase})")
                    }
                }
            }
        } catch (e: Exception) {
            // Terminación normal: tras enviar RECONNECT_INS, Flower cierra el
            // servidor gRPC y cancela las llamadas en vuelo, así que este
            // `collect` acaba con `UNAVAILABLE: Cancelling all calls`. Sin
            // este filtro, la despedida correcta del servidor viajaba al
            // `catch` de FederatedLearningService, se marcaba como "Sesión
            // federada abortada" y se saltaba `evaluateHeldOutTest()`: la
            // medición sobre el test ciego —el número de la tesis— no se
            // calculaba jamás.
            //
            // Sólo se ignora si el servidor YA se había despedido. Un corte de
            // red a mitad de ronda tiene que seguir propagándose como error.
            if (!despedidaRecibida) throw e
            Log.i(TAG, "El servidor cerró el canal tras despedirse (${e.message}); " +
                "es la terminación normal de la sesión")
        } finally {
            clientMessages.close()
        }
    }

    // ------------------------------------------------------------------
    // Manejadores del protocolo
    // ------------------------------------------------------------------

    private fun handleGetParameters(): ClientMessage =
        ClientMessage.newBuilder().setGetParametersRes(
            GetParametersRes.newBuilder()
                .setStatus(okStatus())
                .setParameters(encodeEncoder(modelManager.getEncoderWeights()))
                .build()
        ).build()

    private suspend fun handleFit(
        fitIns: FitIns,
        local: LocalDataset,
        round: Int
    ): ClientMessage {
        val tag = "fl_fit_round_$round"
        batteryMonitor.startMeasurement(tag)
        ramMonitor.startMeasurement(tag)
        val startedAt = System.currentTimeMillis()

        val globalEncoder = decodeEncoder(fitIns.parameters)
        val config = fitIns.configMap
        // `takeIf { it > 0 }` NO es defensivo de más: los getters escalares de
        // proto3 devuelven el valor por defecto (0), nunca null, cuando el
        // `oneof` apunta a otro campo. Sin esto, un `?:` jamás cae al
        // manifiesto ante un desajuste de proto —sólo entrega un cero
        // silencioso— que es exactamente cómo 18 rondas entrenaron con
        // epochs=0 sin un error en ningún log.
        val epochs = config["local_epochs"]?.sint64?.toInt()
            ?.takeIf { it > 0 } ?: manifest.localEpochs
        val learningRate = config["lr"]?.double?.toFloat()
            ?.takeIf { it > 0f } ?: manifest.learningRate

        val result = localTrainer.fit(
            globalEncoder = globalEncoder,
            trainWindows = local.train,
            learningRate = learningRate,
            epochs = epochs,
            // Semilla distinta por ronda: el barajado y el muestreo de
            // impostores deben variar, a diferencia de la partición.
            seed = clientIdentity.splitSeed + round
        )

        val durationMs = System.currentTimeMillis() - startedAt
        recordResourceUsage(tag, "training", durationMs, eer = -1.0)

        Log.i(
            TAG,
            "fit ronda $round: ${result.steps} pasos, epochs=$epochs, " +
                "loss=${result.loss}, n=${result.numExamples}, lr=$learningRate, " +
                "max|dEncoder|=${maxAbsDelta(globalEncoder, result.encoderWeights)}"
        )

        return ClientMessage.newBuilder().setFitRes(
            FitRes.newBuilder()
                .setStatus(okStatus())
                .setParameters(encodeEncoder(result.encoderWeights))
                .setNumExamples(result.numExamples.toLong())
                .putMetrics("train_loss", doubleScalar(result.loss.toDouble()))
                .putMetrics("recon_loss", doubleScalar(result.reconLoss.toDouble()))
                .putMetrics("cls_loss", doubleScalar(result.clsLoss.toDouble()))
                .putMetrics("duration_ms", doubleScalar(durationMs.toDouble()))
                .build()
        ).build()
    }

    private suspend fun handleEvaluate(
        evaluateIns: EvaluateIns,
        local: LocalDataset,
        round: Int
    ): Pair<ClientMessage, RoundProgress> {
        val tag = "fl_eval_round_$round"
        batteryMonitor.startMeasurement(tag)
        ramMonitor.startMeasurement(tag)
        val startedAt = System.currentTimeMillis()

        val result = localEvaluator.evaluate(
            globalEncoder = decodeEncoder(evaluateIns.parameters),
            evaluationWindows = local.validation,
            genuineTrainWindows = local.train,
            seed = clientIdentity.splitSeed + round
        )

        val durationMs = System.currentTimeMillis() - startedAt
        recordResourceUsage(tag, "fl_round", durationMs, eer = result.eer)

        val message = ClientMessage.newBuilder().setEvaluateRes(
            EvaluateRes.newBuilder()
                .setStatus(okStatus())
                .setLoss(result.loss)
                .setNumExamples(result.numExamples.toLong())
                // El servidor selecciona la mejor ronda con `val_auc`. Los
                // nombres llevan el prefijo `val_` a propósito: dejan
                // constancia en los logs de que NO proceden del conjunto de
                // test, que permanece ciego durante toda la federación.
                .putMetrics("val_auc", doubleScalar(result.auc))
                .putMetrics("val_eer", doubleScalar(result.eer))
                .putMetrics("val_far", doubleScalar(result.far))
                .putMetrics("val_frr", doubleScalar(result.frr))
                .putMetrics("val_accuracy", doubleScalar(result.accuracy))
                .putMetrics(
                    "calibrated_threshold",
                    doubleScalar(result.calibratedThreshold.toDouble())
                )
                .build()
        ).build()

        return message to RoundProgress(
            round = round,
            auc = result.auc,
            eer = result.eer,
            threshold = result.calibratedThreshold
        )
    }

    // ------------------------------------------------------------------
    // Serialización
    // ------------------------------------------------------------------

    private fun encodeEncoder(weights: FloatArray): Parameters =
        Parameters.newBuilder()
            .setTensorType(TENSOR_TYPE)
            .addTensors(ByteString.copyFrom(NpyHelper.toNpy(weights)))
            .build()

    private fun decodeEncoder(parameters: Parameters): FloatArray {
        require(parameters.tensorsCount == 1) {
            "Se esperaba 1 tensor (el vector plano del encoder), llegaron " +
                "${parameters.tensorsCount}. ¿El servidor sigue enviando el " +
                "modelo tensor a tensor?"
        }
        val weights = NpyHelper.fromNpy(parameters.getTensors(0).asReadOnlyByteBuffer())
        require(weights.size == manifest.encoderFlatSize) {
            "El servidor envió ${weights.size} floats, el modelo local espera " +
                "${manifest.encoderFlatSize}. Servidor y APK no comparten artefacto."
        }
        return weights
    }

    /**
     * Cuánto se movió el encoder durante el `fit`.
     *
     * Es el testigo de que la ronda entrenó de verdad. Un 0.0 exacto significa
     * que el encoder volvió al servidor tal y como llegó, y que esta ronda no
     * aporta nada a FedAvg.
     */
    private fun maxAbsDelta(before: FloatArray, after: FloatArray): Float {
        if (before.size != after.size) return Float.NaN
        var max = 0f
        for (i in before.indices) {
            val d = kotlin.math.abs(after[i] - before[i])
            if (d > max) max = d
        }
        return max
    }

    private fun okStatus(): Status =
        Status.newBuilder().setCode(Status.Code.OK).build()

    private fun doubleScalar(value: Double): Scalar =
        Scalar.newBuilder().setDouble(value).build()

    private suspend fun recordResourceUsage(
        tag: String,
        operationType: String,
        durationMs: Long,
        eer: Double
    ) {
        val battery = batteryMonitor.stopMeasurement(tag)
        val ram = ramMonitor.stopMeasurement(tag)
        metricsRepository.insert(
            ResourceMeasurementEntity(
                tag = tag,
                operationType = operationType,
                sensorConfig = manifest.sensorConfig,
                batteryDeltaPercent = battery.deltaPercent,
                ramPeakMb = ram.peakUsedMb,
                durationMs = durationMs,
                eerValue = eer,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    fun disconnect() {
        channel?.let { if (!it.isShutdown) it.shutdown() }
        channel = null
    }

    private companion object {
        const val TAG = "FlowerGrpcClient"
        const val TENSOR_TYPE = "numpy.ndarray"
        const val MAX_MESSAGE_BYTES = 100 * 1024 * 1024

        /**
         * Mínimo de ventanas para que la partición por sesión tenga sentido.
         * Con menos, `train` no llena ni un lote y `validation` no da para
         * una curva ROC estable.
         */
        const val MIN_WINDOWS_TO_TRAIN = 60

        /**
         * Por debajo de esto, una métrica sobre el conjunto no dice nada:
         * con 30 positivos el error típico del AUC ronda los 0,08.
         */
        const val MIN_WINDOWS_FOR_STABLE_METRIC = 30

        /**
         * Desplazamiento de semilla para la medición final sobre test.
         *
         * Las rondas usan `splitSeed + round`, así que un valor alto evita
         * colisionar con cualquier ronda y hace la medición reproducible.
         */
        const val TEST_SEED_OFFSET = 1_000_000L
    }
}
