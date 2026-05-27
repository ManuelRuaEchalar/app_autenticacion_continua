package com.example.autenticacioncontinua.domain.sensor

import com.example.autenticacioncontinua.domain.model.AccelerometerData
import kotlinx.coroutines.flow.Flow

interface IAccelerometerSensor {
    fun startListening()
    fun stopListening()
    fun getSensorDataFlow(): Flow<AccelerometerData>
}
