package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity
import com.example.autenticacioncontinua.domain.repository.IResourceMeasurementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResourceMeasurementRepositoryImpl(
    private val db: AppDatabase
) : IResourceMeasurementRepository {

    override suspend fun insert(measurement: ResourceMeasurementEntity) {
        withContext(Dispatchers.IO) {
            db.resourceMeasurementDao().insert(measurement)
        }
    }

    override suspend fun getAll(): List<ResourceMeasurementEntity> {
        return withContext(Dispatchers.IO) {
            db.resourceMeasurementDao().getAll()
        }
    }
}
