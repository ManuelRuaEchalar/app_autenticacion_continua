package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.GyroscopeEntity

@Dao
interface GyroscopeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<GyroscopeEntity>)

    @Query("SELECT * FROM gyroscope_data WHERE dateString = :dateString ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getByDate(dateString: String, limit: Int, offset: Int): List<GyroscopeEntity>

    @Query("SELECT DISTINCT dateString FROM gyroscope_data ORDER BY dateString DESC")
    suspend fun getRecordedDates(): List<String>

    @Query("SELECT COUNT(*) FROM gyroscope_data WHERE dateString = :dateString")
    suspend fun getCountByDate(dateString: String): Int

    @Query("SELECT * FROM gyroscope_data ORDER BY timestamp ASC")
    suspend fun getAll(): List<GyroscopeEntity>

    /** Ver [AccelerometerDao.getSince]. */
    @Query("SELECT * FROM gyroscope_data WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getSince(sinceMs: Long): List<GyroscopeEntity>

    /** Ver [AccelerometerDao.deleteOlderThan]. */
    @Query("DELETE FROM gyroscope_data WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
