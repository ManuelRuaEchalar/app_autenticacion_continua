package com.example.autenticacioncontinua.domain.model

data class DailySessionStat(
    val dateString: String, // e.g., "YYYY-MM-DD"
    val totalMinutesRecorded: Int
)
