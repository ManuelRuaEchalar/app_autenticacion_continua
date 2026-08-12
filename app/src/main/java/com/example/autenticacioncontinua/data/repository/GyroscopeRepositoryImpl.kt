package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.data.local.entity.toEntity
import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GyroscopeRepositoryImpl(
    private val db: AppDatabase
) : IGyroscopeRepository {

    override suspend fun saveGyroscopeData(data: List<GyroscopeData>) {
        withContext(Dispatchers.IO) {
            val entities = data.map { it.toEntity() }
            db.gyroscopeDao().insertAll(entities)
        }
    }

    override suspend fun getDailySessionStat(dateString: String): DailySessionStat? {
        return withContext(Dispatchers.IO) {
            db.sessionStatsDao().getStatByDate(dateString)?.toDomain()
        }
    }

    override suspend fun updateDailySessionStat(stat: DailySessionStat) {
        withContext(Dispatchers.IO) {
            db.sessionStatsDao().insertOrUpdate(stat.toEntity())
        }
    }

    override suspend fun getGyroscopeDataByDate(dateString: String): List<GyroscopeData> {
        return withContext(Dispatchers.IO) {
            db.gyroscopeDao().getByDate(dateString, limit = 500, offset = 0).map { it.toDomain() }
        }
    }

    override suspend fun getRecordedDates(): List<String> {
        return withContext(Dispatchers.IO) {
            db.gyroscopeDao().getRecordedDates()
        }
    }

    override suspend fun getAllGyroscopeData(): List<GyroscopeData> {
        return withContext(Dispatchers.IO) {
            db.gyroscopeDao().getAll().map { it.toDomain() }
        }
    }

    override suspend fun getGyroscopeDataSince(sinceMs: Long): List<GyroscopeData> {
        return withContext(Dispatchers.IO) {
            db.gyroscopeDao().getSince(sinceMs).map { it.toDomain() }
        }
    }

    override suspend fun deleteGyroscopeDataOlderThan(cutoffMs: Long): Int {
        return withContext(Dispatchers.IO) {
            db.gyroscopeDao().deleteOlderThan(cutoffMs)
        }
    }
}
