package com.example.autenticacioncontinua.domain.model

/**
 * Resultado de una sesión de aprendizaje federado, tal y como se muestra al
 * usuario y se guarda en el historial.
 */
data class TrainingRun(
    val id: Long = 0,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val rounds: Int,

    val trainWindows: Int,
    val valWindows: Int,
    val testWindows: Int,
    val sessionCount: Int,

    val lastValAuc: Double,
    val lastValEer: Double,

    val testAuc: Double,
    val testEer: Double,
    val testFar: Double,
    val testFrr: Double,
    val threshold: Float,

    val completed: Boolean,
    val errorMessage: String? = null
) {
    /** ¿Llegó a ejecutarse la medición sobre el conjunto ciego? */
    val hasBlindTest: Boolean get() = testAuc >= 0.0

    /**
     * Con menos de tres sesiones distintas la partición corta por tiempo dentro
     * de una misma grabación y las métricas están infladas por fuga.
     */
    val leakageSuspected: Boolean get() = sessionCount < MIN_SESSIONS

    /**
     * El umbral operativo no sirve: rechaza casi todas las ventanas genuinas.
     * Es compatible con un EER aparentemente bueno, porque el EER barre la ROC
     * y no depende del umbral.
     */
    val thresholdUnusable: Boolean get() = hasBlindTest && testFrr >= 0.9

    val durationMs: Long get() = finishedAtMs - startedAtMs

    companion object {
        const val MIN_SESSIONS = 3
    }
}
