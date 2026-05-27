package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.AccelerometerData

@Entity(tableName = "accelerometer_data")
data class AccelerometerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long,
    val dateString: String
)

fun AccelerometerEntity.toDomain() = AccelerometerData(id, x, y, z, timestamp, dateString)
fun AccelerometerData.toEntity() = AccelerometerEntity(x = x, y = y, z = z, timestamp = timestamp, dateString = dateString)
