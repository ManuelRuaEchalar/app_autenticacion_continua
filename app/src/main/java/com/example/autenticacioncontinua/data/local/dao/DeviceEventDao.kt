package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.DeviceEventEntity

@Dao
interface DeviceEventDao {

    @Insert
    suspend fun insert(event: DeviceEventEntity)

    @Query("SELECT * FROM device_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<DeviceEventEntity>

    @Query("SELECT COUNT(*) FROM device_events WHERE type = :type AND timestampMs >= :since")
    suspend fun countSince(type: String, since: Long): Int

    /**
     * El diario comparte base con los sensores, que ya generan ~650 mil filas
     * al día. Se poda por antigüedad con el mismo criterio que la purga de
     * datos crudos para que no crezca sin techo.
     */
    @Query("DELETE FROM device_events WHERE timestampMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
