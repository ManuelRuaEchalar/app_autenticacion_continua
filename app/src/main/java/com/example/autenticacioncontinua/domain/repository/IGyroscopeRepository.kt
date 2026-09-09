package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.ml.SerieTriaxial
import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData

interface IGyroscopeRepository {
    suspend fun saveGyroscopeData(data: List<GyroscopeData>)
    suspend fun getDailySessionStat(dateString: String): DailySessionStat?
    suspend fun updateDailySessionStat(stat: DailySessionStat)
    suspend fun getGyroscopeDataByDate(dateString: String): List<GyroscopeData>
    suspend fun getAllGyroscopeData(): List<GyroscopeData>

    /** Ver [IAccelerometerRepository.serieDesde]: mismo contrato y mismo motivo. */
    suspend fun serieDesde(
        sinceMs: Long,
        excluir: (Long) -> Boolean = { false }
    ): SerieTriaxial

    suspend fun getRecordedDates(): List<String>

    /** Borra las lecturas anteriores a `cutoffMs`. Devuelve las filas borradas. */
    suspend fun deleteGyroscopeDataOlderThan(cutoffMs: Long): Int
}
