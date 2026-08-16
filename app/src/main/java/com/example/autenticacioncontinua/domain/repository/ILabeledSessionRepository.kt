package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.LabeledSession

/**
 * Registro de quién sostenía el teléfono en cada ráfaga etiquetada.
 *
 * A diferencia del diario de eventos, esto NO puede tragarse los errores: si no
 * se consigue anotar que un tramo es de un impostor, ese tramo acabaría en el
 * conjunto genuino de entrenamiento. Por eso [abrir] propaga la excepción y la
 * captura no llega a empezar.
 */
interface ILabeledSessionRepository {

    /**
     * Anota el comienzo de una ráfaga etiquetada.
     *
     * @return el identificador con el que después se cierra el tramo.
     */
    suspend fun abrir(participantId: String, isOwner: Boolean, note: String): Long

    suspend fun cerrar(id: Long, endMs: Long)

    suspend fun todas(): List<LabeledSession>

    /** Tramos que solapan con `[desde, ahora]`, ya cerrados o no. */
    suspend fun desde(desdeMs: Long): List<LabeledSession>

    suspend fun borrar(id: Long)
}
