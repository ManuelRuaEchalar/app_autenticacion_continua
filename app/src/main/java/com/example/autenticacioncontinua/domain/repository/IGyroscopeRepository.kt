package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData

interface IGyroscopeRepository {
    suspend fun saveGyroscopeData(data: List<GyroscopeData>)
    suspend fun getDailySessionStat(dateString: String): DailySessionStat?
    suspend fun updateDailySessionStat(stat: DailySessionStat)
    suspend fun getGyroscopeDataByDate(dateString: String): List<GyroscopeData>
    suspend fun getAllGyroscopeData(): List<GyroscopeData>

    /** Lecturas desde `sinceMs` (epoch en milisegundos), orden ascendente. */
    suspend fun getGyroscopeDataSince(sinceMs: Long): List<GyroscopeData>
    suspend fun getRecordedDates(): List<String>

    /** Borra las lecturas anteriores a `cutoffMs`. Devuelve las filas borradas. */
    suspend fun deleteGyroscopeDataOlderThan(cutoffMs: Long): Int
}
