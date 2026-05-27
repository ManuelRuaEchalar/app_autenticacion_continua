package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.DailySessionStatEntity

@Dao
interface SessionStatsDao {
    @Query("SELECT * FROM daily_session_stats WHERE dateString = :dateString")
    suspend fun getStatByDate(dateString: String): DailySessionStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: DailySessionStatEntity)
}
