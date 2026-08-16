package com.example.autenticacioncontinua.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autenticacioncontinua.MainActivity
import com.example.autenticacioncontinua.device.protection.ProtectionStatus
import com.example.autenticacioncontinua.domain.model.DeviceEventType
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.session.ISessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps running even when the app is closed.
 *
 * Registers a BroadcastReceiver for screen on/off/unlock events and
 * forwards them to the SessionManager. This is the only reliable way
 * to detect device usage from the background on modern Android.
 */
class DataCollectionService : Service() {

    private val sessionManager: ISessionManager by inject()
    private val accelerometerRepository: IAccelerometerRepository by inject()
    private val gyroscopeRepository: IGyroscopeRepository by inject()
    private val deviceEvents: IDeviceEventRepository by inject()
    private val protectionStatus: ProtectionStatus by inject()

    private val purgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "DataCollectionService"
        private const val CHANNEL_ID = "continuous_auth_channel"
        private const val NOTIFICATION_ID = 1

        /**
         * ¿Hay un servicio de recolección vivo EN ESTE PROCESO?
         *
         * Lo consulta [com.example.autenticacioncontinua.work.ServiceWatchdogWorker].
         * Una bandera estática es exactamente la respuesta correcta aquí: si
         * el sistema mató el proceso, el valor vuelve a `false` solo, que es
         * justo el caso que el vigía tiene que detectar. `ActivityManager
         * .getRunningServices` daría lo mismo con más ceremonia y está
         * obsoleto desde API 26.
         */
        @Volatile
        var estaVivo: Boolean = false
            private set

        /**
         * Retención de datos crudos: algo más que los 14 días que lee
         * `WindowSegmenter.DEFAULT_HISTORY_MS`.
         */
        private const val RETENTION_MS = 21L * 24 * 60 * 60 * 1000
    }

    /**
     * Inner BroadcastReceiver – registered programmatically so it
     * works while the service is alive (even if app UI is closed).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "EVENT: Device unlocked")
                    sessionManager.onDeviceUnlocked()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "EVENT: Screen ON")
                    // Some devices don't fire USER_PRESENT if no lock screen
                    // In that case, treat SCREEN_ON as the unlock signal
                    val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm?.isInteractive == true) {
                        sessionManager.onDeviceUnlocked()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "EVENT: Screen OFF")
                    sessionManager.onScreenOff()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        estaVivo = true
        createNotificationChannel()
        anotarArranque()

        // Register the screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        Log.d(TAG, "Screen receiver registered")

        purgeOldData()
        sessionManager.startMonitoring()

        // If the screen is already on when the service starts, trigger immediately
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Log.d(TAG, "Screen is already ON at service start")
            sessionManager.onDeviceUnlocked()
        }
    }

    /**
     * Borra las lecturas que `WindowSegmenter` ya no puede usar.
     *
     * El ventaneo sólo mira los últimos 14 días, pero nada borraba lo
     * anterior: la base crecía sin techo. Con el presupuesto diario subido a
     * 90 min eso son ~650 mil filas al día entre los dos sensores, así que la
     * purga deja de ser opcional.
     *
     * El margen extra sobre los 14 días es deliberado: da holgura para
     * depurar con datos que el entrenamiento ya no usa, en vez de borrarlos
     * justo en la frontera.
     */
    private fun purgeOldData() {
        purgeScope.launch {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            try {
                val acc = accelerometerRepository.deleteAccelerometerDataOlderThan(cutoff)
                val gyro = gyroscopeRepository.deleteGyroscopeDataOlderThan(cutoff)
                // El diario comparte base con los sensores y se poda con el
                // mismo criterio, para que no acabe siendo lo único que crece
                // sin techo tras haber arreglado justo eso.
                deviceEvents.purgeOlderThan(cutoff)
                if (acc + gyro > 0) {
                    Log.i(TAG, "Purga: $acc filas de acelerómetro y $gyro de " +
                        "giroscopio anteriores a ${RETENTION_MS / 86_400_000} días")
                }
            } catch (e: Exception) {
                // Que falle la purga no debe impedir recolectar.
                Log.e(TAG, "La purga de datos antiguos falló", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        estaVivo = false
        // Se anota ANTES de cancelar `purgeScope`, y en un ámbito propio: si
        // se lanzara en el que está a punto de morir, la anotación se
        // cancelaría a mitad y el diario nunca registraría las muertes, que
        // son justo las que interesan. `NonCancellable` no basta porque el
        // scope se cancela entero unas líneas más abajo.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            deviceEvents.record(
                DeviceEventType.SERVICE_STOPPED,
                "onDestroy — si no fue una parada manual, lo mató el sistema"
            )
        }
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) { }
        sessionManager.stopMonitoring()
        purgeScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Deja constancia del arranque y del estado de las exenciones.
     *
     * Lo segundo es lo que da valor a lo primero: saber que el servicio
     * arrancó a las 15:04 no dice gran cosa; saber que arrancó DESPROTEGIDO
     * explica por qué a las 15:28 ya no estaba.
     */
    private fun anotarArranque() {
        purgeScope.launch {
            deviceEvents.record(DeviceEventType.SERVICE_STARTED)
            val estado = protectionStatus.current()
            deviceEvents.record(
                DeviceEventType.PROTECTION_STATUS,
                "bateria_exenta=${estado.bateriaExenta} " +
                    "xiaomi=${estado.esXiaomi} -> ${estado.resumen}"
            )
            if (!estado.bateriaExenta) {
                Log.w(TAG, "El servicio arranca SIN exención de batería: " +
                    "el sistema puede matarlo en cualquier momento")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Autenticación Continua",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoreo de uso del dispositivo"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Autenticación Continua")
            .setContentText("Monitoreando uso del dispositivo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
