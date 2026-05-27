package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.data.local.entity.toEntity
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccelerometerRepositoryImpl(
    private val db: AppDatabase
) : IAccelerometerRepository {

    override suspend fun saveAccelerometerData(data: List<AccelerometerData>) {
        withContext(Dispatchers.IO) {
            val entities = data.map { it.toEntity() }
            db.accelerometerDao().insertAll(entities)
        }
    }

    override suspend fun getAccelerometerDataByDate(dateString: String): List<AccelerometerData> {
        return withContext(Dispatchers.IO) {
            db.accelerometerDao().getByDate(dateString, limit = 500, offset = 0).map { it.toDomain() }
        }
    }

    override suspend fun getAllAccelerometerData(): List<AccelerometerData> {
        return withContext(Dispatchers.IO) {
            db.accelerometerDao().getAll().map { it.toDomain() }
        }
    }
}
