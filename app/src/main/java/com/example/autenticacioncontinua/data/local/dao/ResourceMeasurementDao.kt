package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity

@Dao
interface ResourceMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: ResourceMeasurementEntity)

    @Query("SELECT * FROM resource_measurements ORDER BY timestampMs DESC")
    suspend fun getAll(): List<ResourceMeasurementEntity>
}
