package com.example.autenticacioncontinua.domain.model

data class GyroscopeData(
    val id: Long = 0,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long,
    val dateString: String = ""
)
