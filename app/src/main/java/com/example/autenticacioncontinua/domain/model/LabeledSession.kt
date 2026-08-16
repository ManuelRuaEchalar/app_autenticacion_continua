package com.example.autenticacioncontinua.domain.model

/**
 * Tramo de grabación atribuido a una persona concreta.
 *
 * Ver `LabeledSessionEntity` para el porqué del diseño.
 */
data class LabeledSession(
    val id: Long = 0,
    val participantId: String,
    val startMs: Long,
    val endMs: Long,
    val isOwner: Boolean,
    val note: String
) {

    /** Ráfaga aún abierta: se insertó al empezar y no llegó a cerrarse. */
    val enCurso: Boolean get() = endMs <= startMs

    /**
     * Fin que debe usarse para excluir muestras, cerrado o no.
     *
     * Una ráfaga abierta se trata como si hubiera durado el máximo posible.
     * El error barato aquí es pasarse excluyendo: sobran ventanas genuinas
     * —el tope de `WindowSegmenter` son 800 y el histórico da varios miles—,
     * mientras que colar ventanas de un impostor en el conjunto genuino
     * envenena el modelo personal en silencio y sin síntoma visible.
     */
    val effectiveEndMs: Long
        get() = if (enCurso) startMs + MAX_ABIERTA_MS else endMs

    val durationMs: Long get() = (effectiveEndMs - startMs).coerceAtLeast(0)

    companion object {
        /**
         * Duración que se supone a una ráfaga que quedó abierta.
         *
         * Holgada respecto a los 3 min de una captura real: si el proceso
         * murió a mitad, no se sabe cuánto llegó a escribirse, y el margen
         * cuesta ventanas genuinas que sobran.
         */
        const val MAX_ABIERTA_MS = 15L * 60 * 1000
    }
}
