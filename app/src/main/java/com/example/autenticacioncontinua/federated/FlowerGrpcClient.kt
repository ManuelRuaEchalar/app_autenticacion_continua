package com.example.autenticacioncontinua.federated

import android.content.Context
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.repository.IResourceMeasurementRepository
import com.example.autenticacioncontinua.ml.metrics.EERCalculator
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.training.LocalTrainer
import com.example.autenticacioncontinua.monitoring.IBatteryMonitor
import com.example.autenticacioncontinua.monitoring.IRamMonitor
import com.google.protobuf.ByteString
import flwr.android_client.*
import io.grpc.ManagedChannel
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class FlowerGrpcClient(
    private val context: Context,
    private val modelManager: TFLiteModelManager,
    private val localTrainer: LocalTrainer,
    private val windowSegmenter: IWindowSegmenter,
    private val eerCalculator: EERCalculator,
    private val batteryMonitor: IBatteryMonitor,
    private val ramMonitor: IRamMonitor,
    private val metricsRepository: IResourceMeasurementRepository
) {
    private lateinit var channel: ManagedChannel

    fun connect(host: String, port: Int = 8080) {
        channel = AndroidChannelBuilder
            .forAddress(host, port)
            .context(context)
            .maxInboundMessageSize(1024 * 1024 * 100) // 100 MB libres para evitar cortes por peso
            .usePlaintext() // Se usa texto plano directo a la EC2
            .build()
    }

    suspend fun runFederatedSession(
        onRoundComplete: (round: Int, eer: Double) -> Unit,
        onStatusUpdate: (status: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val stub = FlowerServiceGrpcKt.FlowerServiceCoroutineStub(channel)
        
        // This is a simplified version of Flower client flow for Kotlin
        // Real Flower client flow joins with a flow of ClientMessages and receives a flow of ServerMessages
        
        // Since stub.join takes a Flow<ClientMessage> and returns a Flow<ServerMessage>, 
        // we use a Channel or flow builder to send messages. For simplicity, we can do:
        
        val clientMessages = kotlinx.coroutines.channels.Channel<ClientMessage>()
        
        val requestFlow = clientMessages.receiveAsFlow()
        
        onStatusUpdate("Conectado a Flower. Esperando instrucciones de AWS...")
        val responseFlow = stub.join(requestFlow)
        
        var roundCount = 0

        responseFlow.collect { serverMessage ->
            roundCount++
            
            // Logueamos qué orden acaba de llegar desde AWS
            android.util.Log.d("FLOWER", "¡AWS Envió un mensaje! Tipo: ${serverMessage.msgCase}")
            
            when (serverMessage.msgCase) {
                ServerMessage.MsgCase.GET_PARAMETERS_INS -> {
                    onStatusUpdate("Enviando parámetros iniciales al servidor...")
                    clientMessages.send(handleGetParameters())
                }
                ServerMessage.MsgCase.FIT_INS -> {
                    onStatusUpdate("Entrenando localmente... (Esto puede tomar unos minutos)")
                    clientMessages.send(handleFit(serverMessage.fitIns, roundCount.toString()))
                }
                ServerMessage.MsgCase.EVALUATE_INS -> {
                    onStatusUpdate("Evaluando rendimiento del modelo local...")
                    val res = handleEvaluate(serverMessage.evaluateIns, roundCount.toString())
                    clientMessages.send(res.first)
                    onRoundComplete(roundCount, res.second)
                }
                else -> {
                    // NUNCA ignores un mensaje. Notificamos si llega algo raro.
                    android.util.Log.e("FLOWER", "Mensaje desconocido o ignorado: ${serverMessage.msgCase}")
                    onStatusUpdate("Advertencia: Mensaje desconocido de AWS (${serverMessage.msgCase})")
                }
            }
        }
        clientMessages.close() // Fix: Close the channel to prevent resource leak
    }

    private suspend fun handleGetParameters(): ClientMessage = withContext(Dispatchers.Default) {
        val params = modelManager.getParameters()
        ClientMessage.newBuilder().setGetParametersRes(
            GetParametersRes.newBuilder()
                .setStatus(Status.newBuilder().setCode(Status.Code.OK).build())
                .setParameters(
                    Parameters.newBuilder()
                        .setTensorType("NDARRAY")
                        .addAllTensors(params.map { ByteString.copyFrom(NpyHelper.wrapWithNpyHeader(it)) })
                        .build()
                )
                .build()
        ).build()
    }

    private suspend fun handleFit(fitIns: FitIns, roundId: String): ClientMessage {
        val tag = "fl_fit_round_$roundId"
        batteryMonitor.startMeasurement(tag)
        ramMonitor.startMeasurement(tag)
        val startTime = System.currentTimeMillis()

        val globalParams = fitIns.parameters.tensorsList.map { NpyHelper.stripNpyHeader(it.asReadOnlyByteBuffer()) }
        
        val windows = windowSegmenter.getWindows(minWindows = 10)

        val result = if (windows != null) {
            localTrainer.train(positiveWindows = windows, globalParams = globalParams)
        } else {
            modelManager.setParameters(globalParams)
            com.example.autenticacioncontinua.ml.training.TrainingResult(globalParams, 0, 0f)
        }

        val durationMs = System.currentTimeMillis() - startTime
        val batteryMeasurement = batteryMonitor.stopMeasurement(tag)
        val ramMeasurement = ramMonitor.stopMeasurement(tag)

        metricsRepository.insert(ResourceMeasurementEntity(
            tag = tag, operationType = "training", sensorConfig = "gyro_acc",
            batteryDeltaPercent = batteryMeasurement.deltaPercent,
            ramPeakMb = ramMeasurement.peakUsedMb,
            durationMs = durationMs, eerValue = -1.0,
            timestampMs = System.currentTimeMillis()
        ))

        return ClientMessage.newBuilder().setFitRes(
            FitRes.newBuilder()
                .setStatus(Status.newBuilder().setCode(Status.Code.OK).build())
                .setParameters(
                    Parameters.newBuilder()
                        .setTensorType("NDARRAY")
                        .addAllTensors(result.updatedParams.map { ByteString.copyFrom(NpyHelper.wrapWithNpyHeader(it)) })
                        .build()
                )
                .setNumExamples(result.numSamples.toLong())
                .setMetrics(
                    Properties.newBuilder().putProperties("loss", Scalar.newBuilder().setDouble(result.loss.toDouble()).build())
                )
                .build()
        ).build()
    }

    private suspend fun handleEvaluate(evaluateIns: EvaluateIns, roundId: String): Pair<ClientMessage, Double> {
        val tag = "fl_eval_round_$roundId"
        batteryMonitor.startMeasurement(tag)
        ramMonitor.startMeasurement(tag)
        val startTime = System.currentTimeMillis()

        val globalParams = evaluateIns.parameters.tensorsList.map { NpyHelper.stripNpyHeader(it.asReadOnlyByteBuffer()) }
        modelManager.setParameters(globalParams)

        val windows = windowSegmenter.getWindows(minWindows = 5)
        var eer = 1.0
        var loss = 0f
        var numExamples = 0L

        if (windows != null) {
            val scores = mutableListOf<Pair<Float, Int>>()
            windows.forEach { w -> scores.add(Pair(modelManager.runInference(w), 1)) }
            
            // Generate some synthetic negatives for evaluation
            val syntheticNegatives = localTrainer.generateSyntheticNegatives(windows)
            syntheticNegatives.forEach { w -> scores.add(Pair(modelManager.runInference(w), 0)) }
            
            eer = eerCalculator.calculate(scores)
            loss = calculateBinaryCrossEntropy(scores)
            numExamples = windows.size.toLong()
        }

        val durationMs = System.currentTimeMillis() - startTime
        val batteryMeasurement = batteryMonitor.stopMeasurement(tag)
        val ramMeasurement = ramMonitor.stopMeasurement(tag)

        metricsRepository.insert(ResourceMeasurementEntity(
            tag = tag, operationType = "fl_round", sensorConfig = "gyro_acc",
            batteryDeltaPercent = batteryMeasurement.deltaPercent,
            ramPeakMb = ramMeasurement.peakUsedMb,
            durationMs = durationMs, eerValue = eer,
            timestampMs = System.currentTimeMillis()
        ))

        val msg = ClientMessage.newBuilder().setEvaluateRes(
            EvaluateRes.newBuilder()
                .setStatus(Status.newBuilder().setCode(Status.Code.OK).build())
                .setLoss(loss)
                .setNumExamples(numExamples)
                .setMetrics(
                    Properties.newBuilder()
                        .putProperties("eer", Scalar.newBuilder().setDouble(eer).build())
                        .build()
                )
                .build()
        ).build()
        
        return Pair(msg, eer)
    }

    private fun calculateBinaryCrossEntropy(scores: List<Pair<Float, Int>>): Float {
        var loss = 0.0
        scores.forEach { (pred, trueLabel) ->
            val p = pred.coerceIn(1e-7f, 1f - 1e-7f)
            loss += -trueLabel * Math.log(p.toDouble()) - (1 - trueLabel) * Math.log(1 - p.toDouble())
        }
        return (loss / scores.size).toFloat()
    }

    fun disconnect() {
        if (::channel.isInitialized && !channel.isShutdown) {
            channel.shutdown()
        }
    }
}
