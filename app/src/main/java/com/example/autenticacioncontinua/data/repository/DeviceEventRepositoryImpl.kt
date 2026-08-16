package com.example.autenticacioncontinua.data.repository

import android.util.Log
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.DeviceEventEntity
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.domain.model.DeviceEvent
import com.example.autenticacioncontinua.domain.model.DeviceEventType
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceEventRepositoryImpl(
    private val db: AppDatabase
) : IDeviceEventRepository {

    override suspend fun record(type: DeviceEventType, detail: String) {
        withContext(Dispatchers.IO) {
            // El diario NUNCA puede tumbar a quien lo está escribiendo. Se
            // registra desde dentro del ciclo de recolección y desde el
            // vigía; una excepción aquí (base bloqueada, disco lleno) no
            // puede llevarse por delante la ráfaga que estaba anotando.
            runCatching {
                db.deviceEventDao().insert(
                    DeviceEventEntity(
                        timestampMs = System.currentTimeMillis(),
                        type = type.name,
                        detail = detail
                    )
                )
            }.onFailure { Log.w(TAG, "No se pudo anotar $type en el diario", it) }
        }
    }

    override suspend fun recent(limit: Int): List<DeviceEvent> = withContext(Dispatchers.IO) {
        runCatching { db.deviceEventDao().getRecent(limit).map { it.toDomain() } }
            .getOrDefault(emptyList())
    }

    override suspend fun countSince(type: DeviceEventType, since: Long): Int =
        withContext(Dispatchers.IO) {
            runCatching { db.deviceEventDao().countSince(type.name, since) }.getOrDefault(0)
        }

    override suspend fun purgeOlderThan(cutoff: Long): Int = withContext(Dispatchers.IO) {
        runCatching { db.deviceEventDao().deleteOlderThan(cutoff) }.getOrDefault(0)
    }

    private companion object {
        const val TAG = "DeviceEventRepository"
    }
}
