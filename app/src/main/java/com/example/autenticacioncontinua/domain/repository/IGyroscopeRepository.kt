package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData

interface IGyroscopeRepository {
    suspend fun saveGyroscopeData(data: List<GyroscopeData>)
    suspend fun getDailySessionStat(dateString: String): DailySessionStat?
    suspend fun updateDailySessionStat(stat: DailySessionStat)
    suspend fun getGyroscopeDataByDate(dateString: String): List<GyroscopeData>
    suspend fun getAllGyroscopeData(): List<GyroscopeData>
    suspend fun getRecordedDates(): List<String>
}
