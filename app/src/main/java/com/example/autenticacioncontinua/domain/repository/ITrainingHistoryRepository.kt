package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.TrainingRun
import kotlinx.coroutines.flow.Flow

interface ITrainingHistoryRepository {
    suspend fun save(run: TrainingRun): Long
    fun observeRecent(limit: Int = 20): Flow<List<TrainingRun>>
    suspend fun getLast(): TrainingRun?
}
