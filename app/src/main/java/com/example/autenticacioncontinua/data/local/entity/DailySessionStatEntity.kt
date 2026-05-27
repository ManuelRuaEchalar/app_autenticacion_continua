package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.DailySessionStat

@Entity(tableName = "daily_session_stats")
data class DailySessionStatEntity(
    @PrimaryKey
    val dateString: String,
    val totalMinutesRecorded: Int
)

fun DailySessionStatEntity.toDomain() = DailySessionStat(dateString, totalMinutesRecorded)
fun DailySessionStat.toEntity() = DailySessionStatEntity(dateString, totalMinutesRecorded)
