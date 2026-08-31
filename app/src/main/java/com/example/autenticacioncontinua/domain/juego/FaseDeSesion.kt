package com.example.autenticacioncontinua.domain.juego

import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity

/**
 * En qué punto de la visita está el participante.
 *
 * NO HAY FASE DE DESCANSO, y es una desviación deliberada del plan del 23/08
 * (decisión del 30/08). El plan preveía tres bloques de cinco minutos separados
 * por pausas de sesenta segundos, unos dieciocho minutos por visita. Con diez
 * visitas por participante eso son tres horas por persona, y con veinte
 * personas, sesenta horas de laboratorio: irrealizable con voluntarios. La
 * sesión se recorta a unos cinco minutos.
 *
 * LO QUE SE CONSERVA de la estructura original, porque es lo que sostiene el
 * análisis y no la duración:
 *
 * - **tres bloques**, que siguen siendo la unidad de remuestreo. El diagnóstico
 *   del 17-18/08 midió que dos ventanas del mismo bloque no son dos
 *   observaciones; los intervalos de confianza se calculan sobre bloques, así
 *   que hacen falta varios por sesión;
 * - **dos en español y uno en latín**, que es el control de si la señal es
 *   motora o en parte cognitiva;
 * - **la rotación de la posición del latín** entre sesiones, sin la cual el
 *   efecto del idioma quedaría confundido con el del cansancio.
 *
 * LO QUE SE PIERDE, y hay que declararlo en las limitaciones: los descansos
 * separaban los bloques en episodios claramente distinguibles y evitaban que la
 * fatiga se acumulara monótonamente dentro de la sesión. Sin ellos, los tres
 * bloques de una visita están más correlacionados entre sí de lo que estarían
 * con pausa, lo que probablemente SUBE el ICC intra-sesión — justo la magnitud
 * que la Propuesta II mide. Es un coste conocido, no un descuido.
 *
 * La aclimatación sí se conserva, recortada a diez segundos: existe para no
 * capturar «novato con este teléfono» en los primeros tecleos, y para eso diez
 * segundos delante del teclado propio bastan.
 */
sealed interface FaseDeSesion {

    /** Cuánto dura esta fase. 0 en [Fin], que no termina sola. */
    val duracionMs: Long

    /**
     * Calentamiento. **No genera fila en la base**: ni bloque, ni eventos.
     *
     * Es la única fase en la que se teclea y no se guarda nada, y es a
     * propósito: las primeras pulsaciones con un teclado desconocido miden la
     * familiaridad con la interfaz, no a la persona.
     */
    data object Aclimatacion : FaseDeSesion {
        override val duracionMs = ACLIMATACION_MS
    }

    /** Uno de los tres bloques cronometrados. [indice] es 0, 1 o 2. */
    data class Bloque(val indice: Int, val idioma: String) : FaseDeSesion {
        override val duracionMs = BloqueEntity.DURACION_MS
    }

    /** Resumen. La sesión ya está cerrada cuando se llega aquí. */
    data object Fin : FaseDeSesion {
        override val duracionMs = 0L
    }

    companion object {
        /**
         * Diez segundos de aclimatación (el plan decía sesenta).
         *
         * Se recorta con el resto de la sesión. Sigue cumpliendo su función
         * —que las primeras pulsaciones torpes no entren en el corpus— porque
         * el teclado es el mismo en los dos terminales y en todas las visitas:
         * lo que hay que reaprender cada vez es mínimo.
         */
        const val ACLIMATACION_MS = 10_000L
    }
}
