package com.example.autenticacioncontinua.domain.juego

/**
 * Una comprobación de la lista previa a la sesión.
 *
 * [automatica] separa lo que el programa puede verificar por su cuenta de lo
 * que sólo puede preguntar. La distinción importa: una casilla que el
 * investigador marca es una afirmación suya, y si el programa PUEDE
 * comprobarlo, dejarlo en manos de una casilla es aceptar una mentira posible
 * en el sitio donde más cara sale.
 */
data class Comprobacion(
    val clave: String,
    val texto: String,
    val automatica: Boolean = false,
    /** Sólo para las automáticas: si el programa la da por buena. */
    val cumplida: Boolean = false,
    /** Sólo para las automáticas: qué se midió, para poder discutirlo. */
    val detalle: String = ""
)

/**
 * La lista de verificación previa a cada visita (P3 del plan, sección 5.6).
 *
 * ### Por qué existe
 *
 * De la primera tanda de agosto salió la lección: dos de tres teléfonos
 * recogieron CERO datos por saltarse dos pasos de configuración, y no se detectó
 * hasta revisar los dispositivos días después porque nada avisaba. Una lista que
 * se recuerda de memoria se cumple el primer día y se olvida el quinto; una que
 * BLOQUEA el inicio se cumple siempre.
 *
 * ### Qué desapareció respecto al plan del 23/08
 *
 * Las cuatro comprobaciones de teclado —IME de serie, autocorrección, texto
 * predictivo, escritura por deslizamiento— ya no están, y no por relajar el
 * protocolo: el minijuego usa su PROPIO teclado, así que no hay teclado del
 * sistema que configurar. Era el confound más grande del montaje y lo quita el
 * diseño, no la disciplina de quien pasa la lista.
 *
 * ### Qué se añadió
 *
 * La etiqueta A/B del terminal. `IdentidadDelDispositivo` ya sabía detectar que
 * falta, pero sólo avisaba: empezar sin ella deja todas las sesiones marcadas
 * como `?` y el diseño cruzado persona × dispositivo —la razón de ser de los dos
 * aparatos— no se puede analizar después.
 *
 * Clase pura: se prueba en la JVM.
 */
object ListaDeVerificacion {

    const val BRILLO = "brillo"
    const val NO_MOLESTAR = "no_molestar"
    const val BATERIA = "bateria"
    const val ETIQUETA = "etiqueta"
    const val PARTICIPANTE = "participante"

    /** Mínimo de batería para empezar, en porcentaje. */
    const val BATERIA_MINIMA = 40f

    /**
     * Construye la lista para el estado actual del terminal.
     *
     * @param bateria porcentaje, o `null` si no se pudo leer. Un nulo NO se da
     *   por bueno: si el instrumento no responde, la comprobación no está hecha.
     * @param etiquetaAsignada si el terminal ya sabe si es el A o el B.
     * @param hayParticipante si hay alguien seleccionado.
     */
    fun para(
        bateria: Float?,
        etiquetaAsignada: Boolean,
        hayParticipante: Boolean
    ): List<Comprobacion> = listOf(
        Comprobacion(
            clave = BATERIA,
            texto = "Bateria por encima del ${BATERIA_MINIMA.toInt()}%",
            automatica = true,
            cumplida = bateria != null && bateria >= BATERIA_MINIMA,
            detalle = bateria?.let { "ahora: ${it.toInt()}%" } ?: "no se pudo leer"
        ),
        Comprobacion(
            clave = ETIQUETA,
            texto = "Este terminal tiene asignada su etiqueta A o B",
            automatica = true,
            cumplida = etiquetaAsignada,
            detalle = if (etiquetaAsignada) "" else "sin asignar: las sesiones saldrian como '?'"
        ),
        Comprobacion(
            clave = PARTICIPANTE,
            texto = "Hay un participante seleccionado",
            automatica = true,
            cumplida = hayParticipante
        ),
        // Las que el programa no puede mirar. El brillo se puede leer, pero no
        // se puede saber si es "el del protocolo": eso es una decisión escrita
        // en la carpeta del investigador, no un valor del sistema.
        Comprobacion(BRILLO, "Brillo fijo al valor del protocolo"),
        Comprobacion(NO_MOLESTAR, "No molestar activado")
    )

    /**
     * Si se puede empezar.
     *
     * Las automáticas tienen que cumplirse de verdad; las manuales, estar
     * marcadas. Una automática incumplida NO se puede saltar marcando una
     * casilla: si el programa mide que la batería está al 30%, que alguien diga
     * que no lo está no la sube.
     */
    fun puedeEmpezar(lista: List<Comprobacion>, marcadas: Set<String>): Boolean =
        lista.all { if (it.automatica) it.cumplida else it.clave in marcadas }

    /** Lo que falta, para poder decirlo en la pantalla en vez de sólo bloquear. */
    fun pendientes(lista: List<Comprobacion>, marcadas: Set<String>): List<Comprobacion> =
        lista.filterNot { if (it.automatica) it.cumplida else it.clave in marcadas }
}
