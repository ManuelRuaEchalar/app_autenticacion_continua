package com.example.autenticacioncontinua.federated

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autenticacioncontinua.BuildConfig
import com.example.autenticacioncontinua.domain.model.TrainingRun
import com.example.autenticacioncontinua.domain.repository.ITrainingHistoryRepository
import com.example.autenticacioncontinua.ml.data.LocalDataset
import com.example.autenticacioncontinua.ml.model.ModelManifest
import com.example.autenticacioncontinua.ml.training.EvaluationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class FederatedLearningService : Service() {

    private val flowerClient: FlowerGrpcClient by inject()
    private val modelInfoFetcher: ModelInfoFetcher by inject()
    private val manifest: ModelManifest by inject()
    private val trainingHistory: ITrainingHistoryRepository by inject()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var flowerHost: String = BuildConfig.FLOWER_HOST
    private var flowerPort: Int = 8080

    companion object {
        private const val TAG = "FederatedLearningService"

        const val ACTION_START = "ACTION_START_FL"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"

        // Broadcast actions for UI
        const val ACTION_FL_PROGRESS = "com.example.autenticacioncontinua.FL_PROGRESS"
        const val ACTION_FL_ERROR = "com.example.autenticacioncontinua.FL_ERROR"
        const val ACTION_FL_DONE = "com.example.autenticacioncontinua.FL_DONE"
        const val ACTION_FL_STATUS = "com.example.autenticacioncontinua.FL_STATUS"
        const val EXTRA_ROUND = "EXTRA_ROUND"
        const val EXTRA_EER = "EXTRA_EER"
        const val EXTRA_AUC = "EXTRA_AUC"
        const val EXTRA_THRESHOLD = "EXTRA_THRESHOLD"
        const val EXTRA_ERROR_MSG = "EXTRA_ERROR_MSG"
        const val EXTRA_STATUS_MSG = "EXTRA_STATUS_MSG"

        // Resultado final, para que la UI muestre números y no sólo un
        // "finalizado exitosamente" que no dice nada al usuario.
        const val EXTRA_ROUNDS = "EXTRA_ROUNDS"
        const val EXTRA_TEST_AUC = "EXTRA_TEST_AUC"
        const val EXTRA_TEST_EER = "EXTRA_TEST_EER"
        const val EXTRA_TEST_FAR = "EXTRA_TEST_FAR"
        const val EXTRA_TEST_FRR = "EXTRA_TEST_FRR"
        const val EXTRA_TEST_N = "EXTRA_TEST_N"
        const val EXTRA_SESSIONS = "EXTRA_SESSIONS"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            flowerHost = intent.getStringExtra(EXTRA_HOST) ?: BuildConfig.FLOWER_HOST
            flowerPort = intent.getIntExtra(EXTRA_PORT, 8080)

            val notification = NotificationCompat.Builder(this, "FL_CHANNEL")
                .setContentTitle("Aprendizaje Federado")
                .setContentText("Conectando con Flower en $flowerHost:$flowerPort...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build()

            startForeground(2, notification)
            startFederatedSession()
        }
        return START_NOT_STICKY
    }

    private fun startFederatedSession() {
        serviceScope.launch {
            val startedAt = System.currentTimeMillis()
            var lastProgress: RoundProgress? = null
            var dataset: LocalDataset? = null
            try {
                if (manifest.scalerIsIdentityPlaceholder) {
                    // El artefacto se exportó sin --scaler-stats: el modelo
                    // recibiría lecturas crudas del sensor en lugar de valores
                    // tipificados y devolvería puntuaciones sin sentido, sin
                    // fallar en ningún sitio. Mejor no participar.
                    broadcastError(
                        "El modelo empaquetado usa un normalizador identidad. " +
                            "Regenera los assets con --scaler-stats antes de entrenar."
                    )
                    stopSelf()
                    return@launch
                }

                // 1. Partición local antes de conectar: si no hay datos, no
                //    tiene sentido ocupar un hueco de cliente en la ronda.
                broadcastStatus("Preparando datos locales...")
                val local = flowerClient.prepareDataset()
                if (local == null) {
                    broadcastError(
                        "No hay suficientes datos recolectados en sesiones distintas. " +
                            "Usa el teléfono con normalidad durante unos días antes de entrenar."
                    )
                    stopSelf()
                    return@launch
                }
                dataset = local
                Log.i(TAG, "Dataset local listo: ${local.summary()}")

                // 2. Compatibilidad con el modelo global (REST).
                broadcastStatus("Verificando compatibilidad con el servidor...")
                modelInfoFetcher.fetchModelInfo().requireCompatibleWith(manifest)

                // 3. Canal gRPC.
                flowerClient.connect(flowerHost, flowerPort)

                // 4. Rondas federadas.
                flowerClient.runFederatedSession(
                    onRoundComplete = { progress ->
                        lastProgress = progress
                        broadcastProgress(progress)
                    },
                    onStatusUpdate = { status -> broadcastStatus(status) }
                )

                // 5. Medición final sobre el conjunto de test.
                //    Es la ÚNICA vez que se toca `dataset.test` en todo el
                //    ciclo: durante las rondas se evalúa sobre validación para
                //    que el early stopping no vea el test. Este es el número
                //    que va a la tesis.
                broadcastStatus("Evaluando sobre el conjunto de test...")
                val finalTest = flowerClient.evaluateHeldOutTest()

                val run = buildRun(startedAt, local, lastProgress, finalTest, null)
                trainingHistory.save(run)
                Log.i(TAG, "Sesión guardada en el historial: $run")
                broadcastDone(run)
            } catch (e: Exception) {
                Log.e(TAG, "Sesión federada abortada", e)
                // También se guarda lo que falló. Un historial que sólo
                // registra los éxitos oculta justo el patrón que interesa
                // cuando algo va mal en el campo.
                dataset?.let { d ->
                    runCatching {
                        trainingHistory.save(
                            buildRun(startedAt, d, lastProgress, null,
                                e.message ?: e::class.java.simpleName)
                        )
                    }
                }
                broadcastError(e.message ?: "Error desconocido")
            } finally {
                flowerClient.disconnect()
                stopSelf()
            }
        }
    }

    private fun broadcastProgress(progress: RoundProgress) {
        val intent = Intent(ACTION_FL_PROGRESS).apply {
            putExtra(EXTRA_ROUND, progress.round)
            putExtra(EXTRA_EER, progress.eer)
            putExtra(EXTRA_AUC, progress.auc)
            putExtra(EXTRA_THRESHOLD, progress.threshold)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(msg: String) {
        sendBroadcast(Intent(ACTION_FL_STATUS).apply { putExtra(EXTRA_STATUS_MSG, msg) })
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(ACTION_FL_ERROR).apply { putExtra(EXTRA_ERROR_MSG, msg) })
    }

    /**
     * Reúne el resultado de la sesión para el historial.
     *
     * Se guarda siempre: completada o abortada. Un historial que sólo registra
     * los éxitos oculta justo el patrón que interesa cuando algo falla en el
     * campo, donde no hay logcat a mano.
     */
    private fun buildRun(
        startedAt: Long,
        dataset: LocalDataset,
        lastProgress: RoundProgress?,
        finalTest: EvaluationResult?,
        error: String?
    ): TrainingRun = TrainingRun(
        startedAtMs = startedAt,
        finishedAtMs = System.currentTimeMillis(),
        rounds = lastProgress?.round ?: 0,
        trainWindows = dataset.train.size,
        valWindows = dataset.validation.size,
        testWindows = dataset.test.size,
        sessionCount = dataset.sessionCount,
        lastValAuc = lastProgress?.auc ?: -1.0,
        lastValEer = lastProgress?.eer ?: -1.0,
        // -1 marca "no llegó a medirse", que es distinto de "midió 0".
        testAuc = finalTest?.auc ?: -1.0,
        testEer = finalTest?.eer ?: -1.0,
        testFar = finalTest?.far ?: -1.0,
        testFrr = finalTest?.frr ?: -1.0,
        threshold = finalTest?.calibratedThreshold ?: lastProgress?.threshold ?: -1f,
        completed = error == null,
        errorMessage = error
    )

    private fun broadcastDone(run: TrainingRun) {
        sendBroadcast(
            Intent(ACTION_FL_DONE).apply {
                putExtra(EXTRA_ROUNDS, run.rounds)
                putExtra(EXTRA_TEST_AUC, run.testAuc)
                putExtra(EXTRA_TEST_EER, run.testEer)
                putExtra(EXTRA_TEST_FAR, run.testFar)
                putExtra(EXTRA_TEST_FRR, run.testFrr)
                putExtra(EXTRA_TEST_N, run.testWindows)
                putExtra(EXTRA_SESSIONS, run.sessionCount)
                putExtra(EXTRA_THRESHOLD, run.threshold)
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "FL_CHANNEL",
                "Federated Learning",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        flowerClient.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
