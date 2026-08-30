package com.example.autenticacioncontinua.monitoring

import android.os.SystemClock

/**
 * Estadísticas de una serie de duraciones, en milisegundos.
 *
 * Se reporta la MEDIANA y los percentiles además de la media porque las
 * latencias en un móvil tienen cola larga: una recolección de basura o una
 * bajada de frecuencia del procesador producen valores atípicos que arrastran
 * la media y no representan el comportamiento habitual.
 */
data class EstadisticaLatencia(
    val etiqueta: String,
    val n: Int,
    val mediaMs: Double,
    val medianaMs: Double,
    val p95Ms: Double,
    val minMs: Double,
    val maxMs: Double
) {
    companion object {
        fun desde(etiqueta: String, duracionesMs: List<Double>): EstadisticaLatencia {
            require(duracionesMs.isNotEmpty()) { "no hay duraciones que resumir" }
            val orden = duracionesMs.sorted()
            return EstadisticaLatencia(
                etiqueta = etiqueta,
                n = orden.size,
                mediaMs = orden.average(),
                medianaMs = percentil(orden, 50.0),
                p95Ms = percentil(orden, 95.0),
                minMs = orden.first(),
                maxMs = orden.last()
            )
        }

        /**
         * Percentil por interpolación lineal sobre la lista YA ORDENADA.
         * Con n=1 devuelve ese único valor; con n=2 interpola entre los dos.
         */
        internal fun percentil(ordenadas: List<Double>, p: Double): Double {
            if (ordenadas.size == 1) return ordenadas.first()
            val pos = (p / 100.0) * (ordenadas.size - 1)
            val bajo = pos.toInt()
            val alto = minOf(bajo + 1, ordenadas.size - 1)
            val frac = pos - bajo
            return ordenadas[bajo] * (1 - frac) + ordenadas[alto] * frac
        }
    }
}

/**
 * Acumula duraciones por etiqueta.
 *
 * POR QUÉ EXISTE. Las latencias —inferencia por ventana, entrenamiento local
 * por ronda, ronda federada, extremo a extremo— son variables dependientes de
 * las Propuestas I y II, y hasta ahora sólo se registraba una duración suelta
 * por operación, sin estadística. Aquí se acumulan y se resumen.
 *
 * RELOJ MONÓTONO EN NANOSEGUNDOS. `elapsedRealtimeNanos` no salta con los
 * cambios de hora y tiene resolución de sobra para una inferencia de pocos
 * milisegundos, que con `currentTimeMillis` se mediría con un error relativo
 * enorme.
 *
 * Es seguro llamarlo desde varios hilos: el entrenamiento local y la inferencia
 * corren en distintos despachadores.
 */
class Cronometro {

    private val muestras = mutableMapOf<String, MutableList<Double>>()

    /** Ejecuta [bloque], registra cuánto tardó y devuelve su resultado. */
    inline fun <T> medir(etiqueta: String, bloque: () -> T): T {
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return bloque()
        } finally {
            // En `finally` a propósito: si el bloque lanza, la duración hasta el
            // fallo sigue siendo información útil, y perderla dejaría un hueco
            // silencioso en la serie.
            registrarNanos(etiqueta, SystemClock.elapsedRealtimeNanos() - t0)
        }
    }

    fun registrarNanos(etiqueta: String, nanos: Long) {
        synchronized(muestras) {
            muestras.getOrPut(etiqueta) { mutableListOf() } += nanos / 1_000_000.0
        }
    }

    fun resumen(etiqueta: String): EstadisticaLatencia? = synchronized(muestras) {
        muestras[etiqueta]?.takeIf { it.isNotEmpty() }
            ?.let { EstadisticaLatencia.desde(etiqueta, it.toList()) }
    }

    fun resumenes(): List<EstadisticaLatencia> = synchronized(muestras) {
        muestras.filterValues { it.isNotEmpty() }
            .map { (etiqueta, valores) -> EstadisticaLatencia.desde(etiqueta, valores.toList()) }
    }

    fun limpiar() = synchronized(muestras) { muestras.clear() }
}
