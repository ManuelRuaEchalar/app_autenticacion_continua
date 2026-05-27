package com.example.autenticacioncontinua.domain.session

interface ISessionManager {
    fun onDeviceUnlocked()
    fun onScreenOff()
    fun startMonitoring()
    fun stopMonitoring()
    fun getState(): SessionState
}

enum class SessionState {
    IDLE,
    MONITORING_USAGE,
    RECORDING,
    DAILY_LIMIT_REACHED
}
