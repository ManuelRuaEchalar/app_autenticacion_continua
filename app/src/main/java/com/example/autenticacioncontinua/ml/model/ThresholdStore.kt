package com.example.autenticacioncontinua.ml.model

import android.content.Context

/**
 * Umbral de decisión personal, persistido entre ejecuciones.
 *
 * Es el `thr_calib` de `mejor.py`: se obtiene cruzando las puntuaciones de
 * las ventanas genuinas de entrenamiento con las del pool de background de
 * CALIBRACIÓN, sin mirar jamás las etiquetas del conjunto que después se
 * evalúa.
 *
 * Importa distinguirlo del umbral "oracle" del cuadernillo, que se elige a
 * posteriori sobre el test buscando el punto FAR = FRR. Ese número es una
 * cota inferior del error alcanzable, no algo reproducible en un dispositivo:
 * en producción no existen etiquetas de test con las que optimizar nada. El
 * umbral que se guarda aquí es el único que un sistema desplegado puede
 * aplicar de verdad.
 */
class ThresholdStore(context: Context, private val populationDefault: Float) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Umbral calibrado del usuario, o el poblacional si aún no hay uno. */
    val threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, populationDefault)

    val isCalibrated: Boolean
        get() = prefs.contains(KEY_THRESHOLD)

    val calibratedAtMs: Long
        get() = prefs.getLong(KEY_CALIBRATED_AT, 0L)

    fun save(threshold: Float) {
        if (!threshold.isFinite() || threshold <= 0f || threshold >= 1f) return
        prefs.edit()
            .putFloat(KEY_THRESHOLD, threshold)
            .putLong(KEY_CALIBRATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_THRESHOLD).remove(KEY_CALIBRATED_AT).apply()
    }

    private companion object {
        const val PREFS_NAME = "fedper_threshold"
        const val KEY_THRESHOLD = "calibrated_threshold"
        const val KEY_CALIBRATED_AT = "calibrated_at_ms"
    }
}
