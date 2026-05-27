package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "resource_measurements")
data class ResourceMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
    val operationType: String,
    val sensorConfig: String,
    val batteryDeltaPercent: Float,
    val ramPeakMb: Float,
    val durationMs: Long,
    val eerValue: Double,
    val timestampMs: Long
)
