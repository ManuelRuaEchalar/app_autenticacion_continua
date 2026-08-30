package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Contexto de la sesión, muestreado con poca frecuencia.
 *
 * QUÉ SON Y QUÉ NO SON. Luz, proximidad y temperatura de batería NO entran
 * como entrada del modelo: serían la fuga perfecta. Un clasificador que
 * aprendiera "esta sesión se grabó con 400 lux" separaría sesiones —y por tanto
 * personas, porque cada persona vino a una hora— sin haber aprendido nada de
 * comportamiento. Es exactamente el mecanismo que hundió la recogida ambiental,
 * donde el 85.4% de la varianza entre ráfagas era de sesión y contexto.
 *
 * Sirven para lo contrario: para COMPROBAR que las condiciones fueron
 * comparables entre sesiones y entre participantes, y para poder decir en la
 * memoria, con números, que no lo fueron cuando no lo sean. Una diferencia de
 * rendimiento entre dos participantes es más creíble si se puede enseñar que
 * ambos tecleaban con la misma luz y el teléfono a la misma temperatura.
 *
 * CADENCIA BAJA. Una fila cada pocos segundos, no a 100 Hz. Son variables que
 * cambian despacio, y muestrearlas rápido sólo añadiría filas y consumo — que
 * en este proyecto es una variable dependiente.
 */
@Entity(
    tableName = "covariables_sesion",
    foreignKeys = [
        ForeignKey(
            entity = SesionControladaEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sesionId", "tMs"])]
)
data class CovariableSesionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val sesionId: Long,
    val tMs: Long,

    /** Lux. Nulo si el terminal no tiene sensor de luz ambiente. */
    val luz: Float? = null,
    /** Centímetros, o el valor binario cerca/lejos según el terminal. */
    val proximidad: Float? = null,
    /** Grados centígrados. */
    val tempBateria: Float? = null,
    /** Porcentaje de batería en ese instante. */
    val bateria: Float? = null
) {
    companion object {
        /**
         * Cinco segundos. Da 180 filas por sesión de 15 minutos: suficiente
         * para caracterizar las condiciones y despreciable frente a los 22
         * millones de muestras inerciales.
         */
        const val PERIODO_MS = 5_000L
    }
}
