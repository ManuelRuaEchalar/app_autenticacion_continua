package com.example.autenticacioncontinua.domain.session

/**
 * Política de recolección, en un solo sitio.
 *
 * Estos tres números estaban duplicados: el valor real en
 * [SessionManagerImpl] y el texto que ve el usuario cableado en
 * `MainActivity`. Al trocear la recolección en ráfagas, la UI siguió
 * anunciando "2 minutos de uso continuo" y "15 / 15 min" mientras el
 * recolector ya trabajaba con otros valores — es decir, la pantalla mentía
 * sobre lo que hacía la app, que en un estudio con usuarios es peor que un
 * número feo.
 */
object CollectionPolicy {

    /** Pantalla encendida de forma continua antes de arrancar una ráfaga. */
    const val REQUIRED_CONTINUOUS_USAGE_MS = 25 * 1000L

    /** Duración máxima de una ráfaga; cada una será una sesión distinta. */
    const val MAX_BOUT_MINUTES = 3

    /**
     * Separación entre ráfagas, para repartirlas a lo largo del día.
     *
     * Para verificar en una sentada que se encadenan varias ráfagas en el
     * mismo proceso, bajar esto a 2 min y [MAX_BOUT_MINUTES] a 1: es el mismo
     * camino de código (la ráfaga agota su plazo → `finishBout` →
     * enfriamiento → siguiente ráfaga) en una vigésima parte del tiempo.
     * Acordarse de revertir antes de repartir el APK.
     */
    const val COOLDOWN_MS = 45 * 60 * 1000L

    /** Presupuesto diario de grabación. */
    const val DAILY_LIMIT_MINUTES = 90

    val requiredContinuousUsageSeconds: Int
        get() = (REQUIRED_CONTINUOUS_USAGE_MS / 1000).toInt()

    val cooldownMinutes: Int
        get() = (COOLDOWN_MS / 60_000).toInt()
}
