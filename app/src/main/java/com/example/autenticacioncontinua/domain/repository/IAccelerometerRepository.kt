package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.AccelerometerData

interface IAccelerometerRepository {
    suspend fun saveAccelerometerData(data: List<AccelerometerData>)
    suspend fun getAccelerometerDataByDate(dateString: String): List<AccelerometerData>
    suspend fun getAllAccelerometerData(): List<AccelerometerData>
}
