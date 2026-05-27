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
import com.example.autenticacioncontinua.domain.session.ISessionManager
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

    companion object {
        private const val TAG = "DataCollectionService"
        private const val CHANNEL_ID = "continuous_auth_channel"
        private const val NOTIFICATION_ID = 1
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
        createNotificationChannel()

        // Register the screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        Log.d(TAG, "Screen receiver registered")

        sessionManager.startMonitoring()

        // If the screen is already on when the service starts, trigger immediately
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Log.d(TAG, "Screen is already ON at service start")
            sessionManager.onDeviceUnlocked()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) { }
        sessionManager.stopMonitoring()
        Log.d(TAG, "Service destroyed")
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
