package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.monitoring.EstadisticaLatencia

/**
 * Resumen estadístico de una serie de latencias.
 *
 * POR QUÉ SE GUARDA EL RESUMEN Y NO CADA MEDIDA. Una sesión de inferencia
 * continua produce decenas de miles de latencias por ventana; guardarlas todas
 * llenaría la base sin añadir nada, porque lo que se reporta son los
 * estadísticos. El [Cronometro] acumula en memoria y aquí se persiste el
 * resumen.
 *
 * POR QUÉ MEDIANA Y p95 ADEMÁS DE LA MEDIA. Las latencias en un móvil tienen
 * cola larga: una recolección de basura o una bajada de frecuencia del
 * procesador meten valores atípicos que arrastran la media. Medido en las
 * pruebas: cuatro inferencias de ~10 ms y una pausa de 500 ms dan mediana 10.5
 * y media 108. Reportar sólo la media daría una impresión falsa del
 * comportamiento habitual.
 */
@Entity(
    tableName = "mediciones_latencia",
    indices = [Index(value = ["tMs"]), Index(value = ["configSensores"])]
)
data class MedicionLatenciaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** inferencia_ventana | entrenamiento_local | ronda_fl | extremo_a_extremo */
    val etiqueta: String,
    val configSensores: String,
    val regimenAprendizaje: String,

    val n: Int,
    val mediaMs: Double,
    val medianaMs: Double,
    val p95Ms: Double,
    val minMs: Double,
    val maxMs: Double,

    val tMs: Long
) {
    companion object {
        fun desde(
            estadistica: EstadisticaLatencia,
            configSensores: String,
            regimenAprendizaje: String,
            tMs: Long = System.currentTimeMillis()
        ) = MedicionLatenciaEntity(
            etiqueta = estadistica.etiqueta,
            configSensores = configSensores,
            regimenAprendizaje = regimenAprendizaje,
            n = estadistica.n,
            mediaMs = estadistica.mediaMs,
            medianaMs = estadistica.medianaMs,
            p95Ms = estadistica.p95Ms,
            minMs = estadistica.minMs,
            maxMs = estadistica.maxMs,
            tMs = tMs
        )
    }
}
