package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.AccelerometerEntity

@Dao
interface AccelerometerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<AccelerometerEntity>)

    @Query("SELECT * FROM accelerometer_data WHERE dateString = :dateString ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getByDate(dateString: String, limit: Int, offset: Int): List<AccelerometerEntity>

    @Query("SELECT * FROM accelerometer_data ORDER BY timestamp ASC")
    suspend fun getAll(): List<AccelerometerEntity>
}
