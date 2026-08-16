package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.LabeledSessionEntity

@Dao
interface LabeledSessionDao {

    /** @return el `rowId` asignado, que es el `id` con el que se cerrará. */
    @Insert
    suspend fun insert(session: LabeledSessionEntity): Long

    @Query("UPDATE labeled_sessions SET endMs = :endMs WHERE id = :id")
    suspend fun close(id: Long, endMs: Long)

    @Query("SELECT * FROM labeled_sessions ORDER BY startMs DESC")
    suspend fun getAll(): List<LabeledSessionEntity>

    /**
     * Tramos que pueden solapar con el histórico que lee `WindowSegmenter`.
     *
     * El filtro es por `startMs` y no por el intervalo completo para que el
     * índice sirva; se acepta traer de más y descartar en memoria, porque
     * estas filas se cuentan por decenas.
     */
    @Query("SELECT * FROM labeled_sessions WHERE startMs >= :sinceMs ORDER BY startMs ASC")
    suspend fun getSince(sinceMs: Long): List<LabeledSessionEntity>

    @Query("DELETE FROM labeled_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
