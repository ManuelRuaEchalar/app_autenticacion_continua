package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.TrainingRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingRunDao {

    @Insert
    suspend fun insert(run: TrainingRunEntity): Long

    /** Historial para la UI, lo más reciente primero. */
    @Query("SELECT * FROM training_runs ORDER BY finishedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrainingRunEntity>>

    @Query("SELECT * FROM training_runs ORDER BY finishedAtMs DESC LIMIT 1")
    suspend fun getLast(): TrainingRunEntity?

    @Query("SELECT COUNT(*) FROM training_runs")
    suspend fun count(): Int
}
