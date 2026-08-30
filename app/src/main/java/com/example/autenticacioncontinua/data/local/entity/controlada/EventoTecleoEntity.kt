package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una pulsación: qué se esperaba, qué llegó, cuándo bajó y cuándo subió el dedo.
 *
 * DOS MARCAS DE TIEMPO POR TECLA, NO UNA. De `tDownMs` y `tUpMs` salen las dos
 * magnitudes clásicas de la dinámica de tecleo: el tiempo de permanencia
 * (`tUp - tDown` de una tecla) y el de vuelo (`tDown` de la siguiente menos
 * `tUp` de esta). Con una sola marca por tecla, la permanencia no existe, y es
 * justo la que menos depende del texto que se está copiando.
 *
 * [presion] y [area] son NULOS a propósito. Hay terminales que devuelven
 * siempre 1.0 en presión, o que no exponen el área del contacto. Guardar un
 * 1.0 constante como si fuera una medida haría creer al análisis que hay una
 * variable donde sólo hay una constante del fabricante; el nulo obliga a
 * mirarlo. Comprobar cuál es el caso en los dos terminales del estudio es una
 * de las pruebas de la fase 3.
 *
 * NO SE GUARDA EL TEXTO LIBRE DEL PARTICIPANTE. [esperado] y [recibido] son
 * caracteres de un párrafo que se le mostró y que ya está en el corpus
 * empaquetado: no hay nada que el participante escriba por su cuenta, así que
 * no hay contenido personal que pueda acabar en la base.
 */
@Entity(
    tableName = "eventos_tecleo",
    foreignKeys = [
        ForeignKey(
            entity = BloqueEntity::class,
            parentColumns = ["id"],
            childColumns = ["bloqueId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bloqueId", "tDownMs"])]
)
data class EventoTecleoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val bloqueId: Long,

    /** Identificador del párrafo mostrado, del corpus empaquetado. */
    val parrafoId: String,
    /** Posición del carácter dentro del párrafo. */
    val posicion: Int,

    /** Carácter que tocaba escribir. Vacío en un borrado. */
    val esperado: String,
    /** Carácter recibido. Vacío en un borrado. */
    val recibido: String,

    val acierto: Boolean,

    /**
     * Retroceso.
     *
     * Se marca en vez de omitirse: la corrección forma parte de la dinámica de
     * tecleo, y un borrado registrado como acierto o como error falsearía las
     * dos cuentas a la vez.
     */
    val borrado: Boolean = false,

    val tDownMs: Long,
    /** 0 si la tecla seguía pulsada al terminar el bloque. */
    val tUpMs: Long = 0,

    val x: Float? = null,
    val y: Float? = null,
    val presion: Float? = null,
    val area: Float? = null
) {
    /** Tiempo de permanencia, o `null` si la pulsación quedó sin cerrar. */
    val permanenciaMs: Long? get() = if (tUpMs > tDownMs) tUpMs - tDownMs else null
}
