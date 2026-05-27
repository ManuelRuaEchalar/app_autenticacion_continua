package com.example.autenticacioncontinua.federated

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.example.autenticacioncontinua.BuildConfig

class FederatedLearningService : Service() {

    private val flowerClient: FlowerGrpcClient by inject()
    private val modelInfoFetcher: ModelInfoFetcher by inject()
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
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
        const val EXTRA_ERROR_MSG = "EXTRA_ERROR_MSG"
        const val EXTRA_STATUS_MSG = "EXTRA_STATUS_MSG"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            
            val notification = NotificationCompat.Builder(this, "FL_CHANNEL")
                .setContentTitle("Aprendizaje Federado")
                .setContentText("Conectando con Flower en ${BuildConfig.FLOWER_HOST}:8080...")
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
            try {
                // 1. Verificar compatibilidad (REST usando BuildConfig.SERVER_HOST)
                val info = modelInfoFetcher.fetchModelInfo()
                require(info.sensorConfig == "gyro_acc") {
                    "Configuración de sensores incompatible: ${info.sensorConfig}"
                }
                
                // 2. Conectar gRPC directamente a la EC2 (puerto 8080 texto plano)
                flowerClient.connect(BuildConfig.FLOWER_HOST, 8080)
                
                // 3. Ejecutar rondas FL
                flowerClient.runFederatedSession(
                    onRoundComplete = { round, eer ->
                        broadcastProgress(round, eer)
                    },
                    onStatusUpdate = { status ->
                        broadcastStatus(status)
                    }
                )
                
                broadcastDone()
            } catch (e: Exception) {
                broadcastError(e.message ?: "Error desconocido")
            } finally {
                flowerClient.disconnect()
                stopSelf()
            }
        }
    }

    private fun broadcastProgress(round: Int, eer: Double) {
        val intent = Intent(ACTION_FL_PROGRESS).apply {
            putExtra(EXTRA_ROUND, round)
            putExtra(EXTRA_EER, eer)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent(ACTION_FL_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(msg: String) {
        val intent = Intent(ACTION_FL_ERROR).apply {
            putExtra(EXTRA_ERROR_MSG, msg)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDone() {
        sendBroadcast(Intent(ACTION_FL_DONE))
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
