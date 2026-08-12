package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.data.local.entity.toEntity
import com.example.autenticacioncontinua.domain.model.TrainingRun
import com.example.autenticacioncontinua.domain.repository.ITrainingHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TrainingHistoryRepositoryImpl(
    private val db: AppDatabase
) : ITrainingHistoryRepository {

    override suspend fun save(run: TrainingRun): Long = withContext(Dispatchers.IO) {
        db.trainingRunDao().insert(run.toEntity())
    }

    override fun observeRecent(limit: Int): Flow<List<TrainingRun>> =
        db.trainingRunDao().observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getLast(): TrainingRun? = withContext(Dispatchers.IO) {
        db.trainingRunDao().getLast()?.toDomain()
    }
}
