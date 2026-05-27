package com.example.autenticacioncontinua.device.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.autenticacioncontinua.service.DataCollectionService

/**
 * Starts the foreground service automatically when the device boots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                val serviceIntent = Intent(it, DataCollectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.startForegroundService(serviceIntent)
                } else {
                    it.startService(serviceIntent)
                }
            }
        }
    }
}
