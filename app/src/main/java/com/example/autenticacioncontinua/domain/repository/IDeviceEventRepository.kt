package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.DeviceEvent
import com.example.autenticacioncontinua.domain.model.DeviceEventType

/**
 * Diario de a bordo del dispositivo (pendiente A2).
 *
 * Ninguna operación debe poder tumbar lo que está registrando: si escribir el
 * diario falla, se traga el error. Un registro incompleto es un problema
 * menor; una recolección que se cae porque no pudo anotar un evento es un
 * problema grave.
 */
interface IDeviceEventRepository {

    suspend fun record(type: DeviceEventType, detail: String = "")

    suspend fun recent(limit: Int = 100): List<DeviceEvent>

    /** Cuántas veces ocurrió [type] desde [since]. Para la UI: "revivido N
     *  veces hoy" dice más sobre la salud del dispositivo que cualquier otra
     *  cosa que podamos enseñar. */
    suspend fun countSince(type: DeviceEventType, since: Long): Int

    suspend fun purgeOlderThan(cutoff: Long): Int
}
