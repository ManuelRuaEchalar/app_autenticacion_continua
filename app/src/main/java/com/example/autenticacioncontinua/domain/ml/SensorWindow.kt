package com.example.autenticacioncontinua.domain.ml

/**
 * Una ventana de señal IMU lista para el modelo.
 *
 * @param values ventana aplanada en orden C: `[t0c0, t0c1, ... t0c5, t1c0, ...]`,
 *   de tamaño `windowSize * nFeatures`. El orden de canales es el de
 *   `mejor.py`: acelerómetro primero, giroscopio después
 *   (`[acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z]`). Los valores ya vienen
 *   normalizados con el StandardScaler global.
 * @param sessionId identificador de la sesión de uso continuo de la que
 *   procede la ventana. Es lo que permite reproducir el `_session_split` del
 *   cuadernillo: la partición train/val/test se hace por SESIÓN, nunca por
 *   ventana, porque con solapamiento (paso 96 < ventana 128) dos ventanas
 *   contiguas comparten señal y repartirlas entre particiones sería fuga.
 * @param startTimestampMs instante de la primera muestra de la ventana.
 */
data class SensorWindow(
    val values: FloatArray,
    val sessionId: Int,
    val startTimestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorWindow) return false
        return sessionId == other.sessionId &&
            startTimestampMs == other.startTimestampMs &&
            values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + sessionId
        result = 31 * result + startTimestampMs.hashCode()
        return result
    }
}
