package com.example.autenticacioncontinua.domain.session

interface ISessionManager {
    fun onDeviceUnlocked()
    fun onScreenOff()
    fun startMonitoring()
    fun stopMonitoring()
    fun getState(): SessionState

    /** Minutos que faltan para poder grabar otra ráfaga. 0 si no hay espera. */
    fun getCooldownRemainingMinutes(): Int
}

enum class SessionState {
    IDLE,
    MONITORING_USAGE,
    RECORDING,

    /**
     * Ráfaga terminada, esperando al enfriamiento antes de la siguiente.
     *
     * Sin este estado la UI caía al texto de IDLE y anunciaba que grabaría
     * "tras unos segundos de uso continuo" durante los 45 minutos de espera,
     * que es sencillamente falso.
     */
    COOLDOWN,

    DAILY_LIMIT_REACHED
}
