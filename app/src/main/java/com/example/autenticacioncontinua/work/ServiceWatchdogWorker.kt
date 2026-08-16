package com.example.autenticacioncontinua.work

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.autenticacioncontinua.domain.model.DeviceEventType
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import com.example.autenticacioncontinua.service.DataCollectionService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Resucita el servicio de recolección si el sistema lo mató (pendiente A2).
 *
 * POR QUÉ NO BASTA `START_STICKY`. El servicio ya lo devolvía, y aun así el
 * 2026-08-15 se encontró el proceso muerto en el propio equipo de desarrollo,
 * cuatro minutos después de una ráfaga correcta y CON la exención de batería
 * concedida. MIUI mata sin reprogramar: `START_STICKY` es una petición al
 * sistema, no una garantía, y en las capas de fabricante chinas se ignora con
 * frecuencia. WorkManager persiste sus trabajos en su propia base y el sistema
 * los respeta mucho mejor, porque son parte del contrato de JobScheduler.
 *
 * POR QUÉ 15 MINUTOS. Es el mínimo que Android permite para un trabajo
 * periódico; pedir menos no lo acelera, sólo hace que el sistema lo redondee.
 * En el peor caso se pierden 15 min de recolección tras una muerte, frente a
 * los días que se perdieron en agosto.
 *
 * NO LLEVA CONSTRAINTS a propósito. Cualquier condición (red, batería, carga)
 * es una excusa más para que el sistema aplace justo el trabajo cuya razón de
 * ser es correr cuando nada más corre.
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val eventos: IDeviceEventRepository by inject()

    override suspend fun doWork(): Result {
        if (DataCollectionService.estaVivo) {
            // Camino normal y silencioso. No se anota: un evento cada 15 min
            // ahogaría el diario y taparía justo lo que interesa ver.
            return Result.success()
        }

        return try {
            val intent = Intent(applicationContext, DataCollectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Log.w(TAG, "Servicio caído: revivido por el vigía")
            eventos.record(
                DeviceEventType.SERVICE_REVIVED,
                "El servicio no estaba vivo; arrancado por el vigía periódico"
            )
            Result.success()
        } catch (e: Exception) {
            // A partir de Android 12 arrancar un foreground service desde
            // segundo plano puede lanzar ForegroundServiceStartNotAllowed.
            // Se anota y se reintenta: el sistema sí lo permite cuando la app
            // está exenta de optimización de batería, así que este error es
            // además una señal fiable de que falta el pendiente E.
            Log.e(TAG, "No se pudo revivir el servicio", e)
            eventos.record(
                DeviceEventType.SERVICE_REVIVED,
                "FALLÓ al revivir: ${e.javaClass.simpleName}: ${e.message}. " +
                    "Muy probablemente falta la exención de batería."
            )
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val NOMBRE = "vigia_servicio_recoleccion"

        /**
         * Programa el vigía. Idempotente: se puede llamar en cada arranque de
         * la app y en cada BOOT_COMPLETED sin duplicarlo.
         *
         * Se usa KEEP y no REPLACE para no reiniciar la cuenta de 15 min cada
         * vez que el usuario abre la app — con REPLACE, un participante que
         * entra a mirar la pantalla cada poco impediría que el vigía llegara
         * a ejecutarse nunca.
         */
        fun programar(context: Context) {
            val trabajo = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NOMBRE,
                ExistingPeriodicWorkPolicy.KEEP,
                trabajo
            )
            Log.i(TAG, "Vigía programado cada 15 min")
        }
    }
}
