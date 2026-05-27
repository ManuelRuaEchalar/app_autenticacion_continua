package com.example.autenticacioncontinua.domain.sensor

import com.example.autenticacioncontinua.domain.model.GyroscopeData
import kotlinx.coroutines.flow.Flow

interface IGyroscopeSensor {
    fun startListening()
    fun stopListening()
    fun getSensorDataFlow(): Flow<GyroscopeData>
}
