package com.example.autenticacioncontinua.domain.model.controlada

/**
 * Qué terminal le toca a cada visita.
 *
 * EL PROBLEMA QUE RESUELVE. Con dos terminales y 25 participantes, si cada
 * persona usara siempre el mismo, el efecto de la persona y el del aparato
 * quedarían confundidos: exactamente el fallo que hundió la recogida ambiental,
 * donde el 9.2% de la varianza era de dispositivo y no había forma de
 * separarlo. Alternando A, B, A, B… cada participante aporta sesiones en los
 * dos terminales, y el modelo puede estimar los dos efectos por separado.
 *
 * POR QUÉ LOS IMPARES EMPIEZAN POR A Y LOS PARES POR B. Si todo el mundo
 * empezara por A, el terminal A concentraría todas las primeras visitas —las de
 * participantes sin práctica, más lentos y más variables— y el B todas las
 * segundas. El efecto del aparato volvería a estar confundido, ahora con el de
 * aprendizaje. Desfasar el arranque por paridad lo reparte.
 *
 * ES UNA RECOMENDACIÓN, NO UNA IMPOSICIÓN. La aplicación corre en el terminal
 * en el que corre; no puede elegirlo. Lo que hace con esto es AVISAR al
 * investigador de que el teléfono que tiene en la mano no es el que le tocaba,
 * y dejarle seguir de todos modos —un participante que se presenta sin previo
 * aviso con el otro móvil delante es mejor dato que ninguno—, quedando el
 * reparto real registrado en `sesiones_controladas.dispositivoId`.
 */
object PlanDeDispositivos {

    const val DISPOSITIVO_A = "A"
    const val DISPOSITIVO_B = "B"

    /**
     * @param seudonimo del participante, p. ej. `P07`.
     * @param visita número de visita, empezando en 1.
     */
    fun dispositivoEsperado(seudonimo: String, visita: Int): String {
        require(visita >= 1) { "visita=$visita: las visitas empiezan en 1" }
        val empiezaPorA = ordinal(seudonimo) % 2 == 1
        val esVisitaImpar = visita % 2 == 1
        return if (empiezaPorA == esVisitaImpar) DISPOSITIVO_A else DISPOSITIVO_B
    }

    /**
     * Número del participante, sacado de los dígitos del seudónimo.
     *
     * Se usa el seudónimo y no el identificador de la base porque es lo que el
     * investigador tiene escrito en el cuaderno: si un alta se borra y se
     * rehace, el identificador cambia y el reparto cambiaría con él, mientras
     * que `P07` sigue siendo `P07`. Sin dígitos, se cae a la suma de los
     * caracteres, que es estable y determinista.
     */
    internal fun ordinal(seudonimo: String): Int {
        val digitos = seudonimo.filter { it.isDigit() }
        return digitos.toIntOrNull() ?: seudonimo.sumOf { it.code }
    }

    /**
     * Reparto ideal tras [visitas] visitas: cuántas en cada terminal.
     *
     * Sirve para enseñar en la pantalla de progreso lo que debería haber y
     * contrastarlo con lo que hay.
     */
    fun repartoIdeal(seudonimo: String, visitas: Int): Map<String, Int> =
        (1..visitas).map { dispositivoEsperado(seudonimo, it) }
            .groupingBy { it }.eachCount()
}
