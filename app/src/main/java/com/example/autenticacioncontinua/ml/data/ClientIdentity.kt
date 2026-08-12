package com.example.autenticacioncontinua.ml.data

import android.content.Context
import java.util.UUID

/**
 * Identidad local y estable del cliente federado.
 *
 * Guarda dos cosas en preferencias:
 *
 *  - `clientId`: identificador opaco para trazas y métricas. No se envía como
 *    identificador de persona; sólo permite correlacionar rondas del mismo
 *    dispositivo en los logs.
 *  - `splitSeed`: semilla de la partición train/val/test.
 *
 * La semilla TIENE que persistir. Si cambiara entre rondas, sesiones que hoy
 * son de test entrarían mañana en entrenamiento y el conjunto ciego dejaría
 * de serlo tras un puñado de rondas: exactamente la fuga que esta
 * refactorización viene a cerrar, sólo que repartida en el tiempo y mucho más
 * difícil de detectar.
 */
class ClientIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }

    val splitSeed: Long
        get() {
            if (!prefs.contains(KEY_SPLIT_SEED)) {
                val seed = UUID.randomUUID().mostSignificantBits
                prefs.edit().putLong(KEY_SPLIT_SEED, seed).apply()
                return seed
            }
            return prefs.getLong(KEY_SPLIT_SEED, DEFAULT_SEED)
        }

    private companion object {
        const val PREFS_NAME = "fedper_client"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_SPLIT_SEED = "split_seed"
        const val DEFAULT_SEED = 42L
    }
}
