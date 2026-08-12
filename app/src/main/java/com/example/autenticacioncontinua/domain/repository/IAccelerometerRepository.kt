package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.AccelerometerData

interface IAccelerometerRepository {
    suspend fun saveAccelerometerData(data: List<AccelerometerData>)
    suspend fun getAccelerometerDataByDate(dateString: String): List<AccelerometerData>
    suspend fun getAllAccelerometerData(): List<AccelerometerData>

    /** Lecturas desde `sinceMs` (epoch en milisegundos), orden ascendente. */
    suspend fun getAccelerometerDataSince(sinceMs: Long): List<AccelerometerData>

    /** Borra las lecturas anteriores a `cutoffMs`. Devuelve las filas borradas. */
    suspend fun deleteAccelerometerDataOlderThan(cutoffMs: Long): Int
}
