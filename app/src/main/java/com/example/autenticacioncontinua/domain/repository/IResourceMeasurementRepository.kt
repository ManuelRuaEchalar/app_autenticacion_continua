package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity

interface IResourceMeasurementRepository {
    suspend fun insert(measurement: ResourceMeasurementEntity)
    suspend fun getAll(): List<ResourceMeasurementEntity>
}
