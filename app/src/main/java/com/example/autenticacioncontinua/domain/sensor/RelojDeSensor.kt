package com.example.autenticacioncontinua.domain.sensor

import kotlin.math.abs

/** En qué base venía `SensorEvent.timestamp` en este terminal. */
enum class BaseDeReloj {
    /** Lo que dice la documentación: ns desde el arranque (`elapsedRealtimeNanos`). */
    MONOTONO,

    /**
     * Épocas Unix en ns. Lo hacen algunos controladores, y hay que corregirlo:
     * el reloj de pared salta con NTP, así que un timestamp basado en él
     * arrastra los saltos al cálculo de intervalos.
     */
    EPOCA,

    /** Ni una cosa ni la otra. Se descarta el reloj del evento y se usa el propio. */
    DESCONOCIDA
}

/**
 * Convierte el reloj de `SensorEvent` en los dos que necesita el estudio.
 *
 * EL PROBLEMA QUE RESUELVE, y no es teórico. La documentación de Android dice
 * que `SensorEvent.timestamp` son nanosegundos desde el arranque, en la misma
 * base que `SystemClock.elapsedRealtimeNanos()`. Hay controladores que no lo
 * cumplen: unos devuelven épocas Unix en nanosegundos, otros `uptimeNanos` (que
 * NO cuenta el tiempo en suspensión). Si se toma el valor tal cual, la tasa
 * efectiva sale disparatada o la marca de tiempo cae en 1970, y no lo notas
 * hasta que analizas los datos de sesenta días.
 *
 * QUÉ HACE. Con la primera muestra compara el reloj del evento contra el reloj
 * monótono real y decide la base. A partir de ahí:
 *
 * - el tiempo monótono se normaliza a la base del sistema;
 * - el tiempo de pared se DERIVA del monótono desde un ancla tomada al empezar,
 *   en lugar de leerse del sistema en cada muestra.
 *
 * Lo segundo es lo que evita que un salto de NTP a mitad de un bloque de cinco
 * minutos fabrique un hueco o un solapamiento que no existió. El precio es que
 * el tiempo de pared puede desviarse lentamente del real; en bloques de cinco
 * minutos esa deriva es de milisegundos y no le importa a nadie, mientras que
 * un salto de NTP son segundos y sí importa.
 *
 * FUNCIÓN PURA: no lee ningún reloj por su cuenta, se los pasan. Por eso se
 * puede probar entera en la JVM.
 */
class RelojDeSensor {

    var base: BaseDeReloj = BaseDeReloj.DESCONOCIDA
        private set

    private var anclaEventoNs: Long = 0
    private var anclaMonotonoNs: Long = 0
    private var anclaParedMs: Long = 0
    private var anclado = false

    val estaAnclado: Boolean get() = anclado

    /**
     * Fija el ancla con la primera muestra.
     *
     * @param tEventoNs `SensorEvent.timestamp` tal cual.
     * @param tMonotonoAhoraNs `SystemClock.elapsedRealtimeNanos()` leído al recibirla.
     * @param tParedAhoraMs `System.currentTimeMillis()` leído al recibirla.
     */
    fun anclar(tEventoNs: Long, tMonotonoAhoraNs: Long, tParedAhoraMs: Long) {
        base = clasificar(tEventoNs, tMonotonoAhoraNs, tParedAhoraMs)
        anclaEventoNs = tEventoNs
        anclaMonotonoNs = tMonotonoAhoraNs
        anclaParedMs = tParedAhoraMs
        anclado = true
    }

    /**
     * Tiempo monótono normalizado, en la base de `elapsedRealtimeNanos`.
     *
     * Con la base [BaseDeReloj.DESCONOCIDA] se ignora el reloj del evento y se
     * devuelve el del sistema: perder la precisión del controlador es mucho
     * menos grave que construir toda la serie temporal sobre una base que no se
     * entiende.
     */
    fun monotonoNs(tEventoNs: Long, tMonotonoAhoraNs: Long): Long = when (base) {
        BaseDeReloj.MONOTONO -> tEventoNs
        BaseDeReloj.EPOCA -> anclaMonotonoNs + (tEventoNs - anclaEventoNs)
        BaseDeReloj.DESCONOCIDA -> tMonotonoAhoraNs
    }

    /**
     * Tiempo de pared DERIVADO del monótono, no leído del sistema.
     *
     * Es lo que hace que un salto de NTP no aparezca en los datos.
     */
    fun paredMs(tMonotonoNormalizadoNs: Long): Long =
        anclaParedMs + (tMonotonoNormalizadoNs - anclaMonotonoNs) / 1_000_000L

    private fun clasificar(
        tEventoNs: Long,
        tMonotonoAhoraNs: Long,
        tParedAhoraMs: Long
    ): BaseDeReloj {
        if (tEventoNs <= 0) return BaseDeReloj.DESCONOCIDA
        // Un evento acaba de ocurrir, así que su marca no puede estar lejos del
        // reloj correspondiente. La holgura es generosa a propósito: sólo hay
        // que distinguir entre bases que difieren en años, no afinar.
        if (abs(tMonotonoAhoraNs - tEventoNs) < TOLERANCIA_NS) return BaseDeReloj.MONOTONO
        val tParedAhoraNs = tParedAhoraMs * 1_000_000L
        if (abs(tParedAhoraNs - tEventoNs) < TOLERANCIA_NS) return BaseDeReloj.EPOCA
        return BaseDeReloj.DESCONOCIDA
    }

    companion object {
        /**
         * 10 s. Un evento recién entregado no puede llevar más de eso de
         * retraso, y las bases que hay que distinguir difieren en el orden de
         * décadas: no hace falta ser fino.
         */
        const val TOLERANCIA_NS = 10_000_000_000L
    }
}
