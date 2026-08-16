package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.LabeledSession

/**
 * Un tramo de grabación del que se sabe QUIÉN tenía el teléfono en la mano.
 *
 * POR QUÉ EXISTE. El pool de impostores actual viene de otro terminal, así que
 * "persona" y "aparato" son la misma variable y ningún análisis posterior puede
 * separarlas. La única condición que rompe esa confusión es un impostor
 * grabando en el MISMO teléfono que el usuario legítimo: allí el hardware, la
 * tasa de muestreo y el suelo de ruido son constantes entre las dos clases, y
 * lo que quede de separación ya no puede atribuirse al aparato. Esta tabla es
 * lo que permite marcar esos tramos.
 *
 * POR QUÉ UNA TABLA APARTE Y NO UNA COLUMNA EN LOS SENSORES. `accelerometer_data`
 * y `gyroscope_data` llevan del orden de un millón de filas por participante;
 * añadirles una columna obliga a reescribir la tabla entera en la migración,
 * sobre los datos de campo que el proyecto considera irrecuperables. Un tramo
 * [startMs, endMs] cuesta una fila por ráfaga y se resuelve con una
 * comprobación de rango.
 *
 * EL CAMPO [participantId] SE REPITE A PROPÓSITO. Cada ráfaga es una fila, y el
 * mismo impostor hace varias en la misma tarde. Que el identificador se repita
 * es lo que permite después partir por PERSONA y no sólo por sesión: entrenar
 * contra los impostores A y B y medir contra C y D exige saber qué ventanas son
 * de quién.
 */
@Entity(
    tableName = "labeled_sessions",
    // Todas las consultas filtran por solapamiento temporal con el histórico
    // de entrenamiento (`WindowSegmenter` mira los últimos 14 días).
    indices = [Index(value = ["startMs"])]
)
data class LabeledSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Seudónimo de quien sostenía el teléfono. Nunca un nombre real. */
    val participantId: String,

    val startMs: Long,

    /**
     * Fin del tramo, o 0 si la ráfaga no llegó a cerrarse.
     *
     * Se inserta la fila al EMPEZAR, no al terminar. Si el proceso muere a
     * mitad de una captura, el tramo queda abierto — y un tramo abierto de un
     * impostor tiene que seguir excluyéndose del conjunto genuino, porque los
     * datos sí se escribieron. Ver [LabeledSession.effectiveEndMs].
     */
    val endMs: Long,

    /**
     * Si quien grabó es el dueño del teléfono.
     *
     * Distingue las ráfagas de CONTROL de las de impostor. El dueño graba
     * también bajo el mismo protocolo —misma habitación, misma cuenta atrás,
     * misma duración— porque si no, la comparación acabaría siendo "vida
     * ambiental contra sesión dirigida" en vez de "una persona contra otra", y
     * eso no es biometría. Sus tramos se etiquetan pero NO se excluyen del
     * conjunto de entrenamiento: siguen siendo datos genuinos.
     */
    val isOwner: Boolean,

    val note: String
)

fun LabeledSessionEntity.toDomain() = LabeledSession(
    id = id,
    participantId = participantId,
    startMs = startMs,
    endMs = endMs,
    isOwner = isOwner,
    note = note
)
