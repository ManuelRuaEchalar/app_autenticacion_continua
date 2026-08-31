package com.example.autenticacioncontinua.domain.model.controlada

/**
 * Participante, visto desde el dominio.
 *
 * Existe separado de `ParticipanteEntity` porque la interfaz de alta y
 * selección no debe conocer Room: es la misma separación que ya usa
 * `LabeledSession`. Las tablas de gran volumen —muestras inerciales, eventos de
 * tecleo— NO tienen modelo de dominio: pasan de la captura a la base como
 * entidades y ahí se quedan, porque mapear veintitantos millones de filas a un
 * segundo tipo asignaría memoria sin que nadie lea el resultado.
 */
data class Participante(
    val id: Long,
    val seudonimo: String,
    val fechaAltaMs: Long,
    /** Sesiones utilizables que lleva. Lo rellena el repositorio al listar. */
    val sesionesHechas: Int = 0
) {
    companion object {
        /**
         * Diez sesiones por participante en los 60 días de recogida
         * (corrección del usuario, 23/08: la estimación inicial de más era
         * irrealizable con voluntarios).
         */
        const val SESIONES_OBJETIVO = 10
    }

    val completado: Boolean get() = sesionesHechas >= SESIONES_OBJETIVO
}
