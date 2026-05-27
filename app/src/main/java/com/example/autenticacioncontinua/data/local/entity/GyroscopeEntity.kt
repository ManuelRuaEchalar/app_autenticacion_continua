package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.GyroscopeData

@Entity(tableName = "gyroscope_data")
data class GyroscopeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long,
    val dateString: String
)

fun GyroscopeEntity.toDomain() = GyroscopeData(id, x, y, z, timestamp, dateString)
fun GyroscopeData.toEntity() = GyroscopeEntity(x = x, y = y, z = z, timestamp = timestamp, dateString = dateString)
