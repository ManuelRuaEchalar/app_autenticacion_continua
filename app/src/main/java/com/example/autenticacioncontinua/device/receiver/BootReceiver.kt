package com.example.autenticacioncontinua.device.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.autenticacioncontinua.service.DataCollectionService
import com.example.autenticacioncontinua.work.ServiceWatchdogWorker

/**
 * Arranca la recolección cuando el dispositivo termina de encenderse.
 *
 * OJO CON LO QUE ESTE RECEIVER **NO** GARANTIZA. En MIUI, sin el permiso de
 * inicio automático concedido a mano, `BOOT_COMPLETED` NO SE ENTREGA JAMÁS:
 * este código no llega a ejecutarse y no hay forma de que la app se entere.
 * Por eso se programa también el vigía desde aquí —y desde `App.onCreate`—:
 * WorkManager sí sobrevive al reinicio por su cuenta, así que aunque este
 * receiver nunca corra, el vigía acabará levantando el servicio.
 *
 * Es decir: esto es el camino rápido, no la garantía. La garantía es el
 * pendiente E (que el participante conceda el autostart) más el vigía.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context ?: return

        // Primero el vigía: si arrancar el servicio falla (Android 12+ es
        // restrictivo con los foreground services desde segundo plano), al
        // menos queda programado quien lo reintente.
        runCatching { ServiceWatchdogWorker.programar(ctx) }
            .onFailure { Log.e(TAG, "No se pudo programar el vigía en el arranque", it) }

        runCatching {
            val serviceIntent = Intent(ctx, DataCollectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent)
            } else {
                ctx.startService(serviceIntent)
            }
        }.onFailure { Log.e(TAG, "No se pudo arrancar el servicio en el arranque", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
