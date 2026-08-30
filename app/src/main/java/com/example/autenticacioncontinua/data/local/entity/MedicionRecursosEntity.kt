package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.monitoring.ResumenRecursos

/**
 * Resumen de un BLOQUE de medición de recursos.
 *
 * POR QUÉ UNA TABLA NUEVA Y NO AMPLIAR `resource_measurements`. La tabla vieja
 * guarda `batteryDeltaPercent`, que en las bases de campo del 17/08 valió
 * exactamente 0.0 en 669 de sus 676 filas: el porcentaje no resuelve
 * operaciones de segundos. Esas filas siguen ahí como registro histórico de lo
 * que se hizo, pero mezclar en la misma tabla dos magnitudes con semántica
 * distinta —una inservible y otra no— invita a sumarlas por error en el
 * análisis. Tabla aparte, unidades explícitas en el nombre de cada campo.
 *
 * LOS CAMPOS DE ENERGÍA SON NULOS CUANDO NO HAY MEDIDA, nunca cero. Distinguir
 * "no se pudo medir" de "consumió cero" es justamente lo que faltaba antes.
 */
@Entity(
    tableName = "mediciones_recursos",
    // El análisis agrupa por configuración y por régimen, y ordena por tiempo.
    indices = [Index(value = ["tMs"]), Index(value = ["configSensores"])]
)
data class MedicionRecursosEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val etiqueta: String,
    /** inferencia | entrenamiento_local | ronda_fl | reposo | sesion_controlada */
    val tipoOperacion: String,
    /** acc | acc_gyro | acc_gyro_mag | acc_gyro_touch */
    val configSensores: String,
    /** local | global | federado — variable independiente de la Propuesta I */
    val regimenAprendizaje: String,

    val duracionMs: Long,
    val nMuestras: Int,

    val consumoMicroAh: Long?,
    val consumoMicroAhPorHora: Double?,
    val corrienteMediaMicroA: Double?,

    /** Consumo integrando la corriente instantanea. Ver [MetodoConsumo]. */
    val consumoIntegradoMicroAh: Double?,
    val consumoIntegradoMicroAhPorHora: Double?,

    /**
     * CONTADOR_DE_CARGA | INTEGRACION_DE_CORRIENTE | NINGUNO.
     *
     * Viaja con cada fila porque dos bloques medidos con instrumentos
     * distintos no son comparables, y en el Redmi del estudio el contador no
     * resuelve: se mueve en escalones del 1% de la bateria.
     */
    val metodoConsumo: String,

    /** La cifra reportable, en uAh/h. Sale del metodo que corresponda. */
    val tasaConsumoMicroAhPorHora: Double?,

    /**
     * Si la medicion se puede usar en el analisis.
     *
     * Va como COLUMNA y no se deriva de `invalidez` porque las consultas de
     * agregacion filtran por ella sobre miles de filas, y porque la regla ya no
     * es "invalidez vacia": que el contador no resuelva se anota, pero no
     * invalida si la corriente salva la medicion.
     */
    val valida: Boolean,

    val pssMinKb: Long,
    val pssMaxKb: Long,
    val pssMedioKb: Double,

    /**
     * Todo lo que le paso a la medicion, separado por comas.
     *
     * NO ES EL FILTRO DE VALIDEZ —para eso esta [valida]— sino la bitacora del
     * instrumento. Puede traer motivos y aun asi valer: `CONTADOR_SIN_VARIACION`
     * significa que el contador de carga no resolvio el bloque, pero si hubo
     * corriente la cifra sale integrandola. Se guarda el texto y no un booleano
     * porque en el analisis importa CUAL fue el motivo: descartar por "estaba
     * cargando" y anotar que "el contador no se movio" dicen cosas distintas
     * sobre el protocolo.
     */
    val invalidez: String,

    val tMs: Long
) {
    val esValida: Boolean get() = valida

    companion object {
        fun desde(
            resumen: ResumenRecursos,
            tipoOperacion: String,
            configSensores: String,
            regimenAprendizaje: String,
            tMs: Long = System.currentTimeMillis()
        ) = MedicionRecursosEntity(
            etiqueta = resumen.etiqueta,
            tipoOperacion = tipoOperacion,
            configSensores = configSensores,
            regimenAprendizaje = regimenAprendizaje,
            duracionMs = resumen.duracionMs,
            nMuestras = resumen.nMuestras,
            consumoMicroAh = resumen.consumoMicroAh,
            consumoMicroAhPorHora = resumen.consumoMicroAhPorHora,
            corrienteMediaMicroA = resumen.corrienteMediaMicroA,
            consumoIntegradoMicroAh = resumen.consumoIntegradoMicroAh,
            consumoIntegradoMicroAhPorHora = resumen.consumoIntegradoMicroAhPorHora,
            metodoConsumo = resumen.metodoConsumo.name,
            tasaConsumoMicroAhPorHora = resumen.tasaConsumoMicroAhPorHora,
            valida = resumen.esValida,
            pssMinKb = resumen.pssMinKb,
            pssMaxKb = resumen.pssMaxKb,
            pssMedioKb = resumen.pssMedioKb,
            invalidez = resumen.invalidez.joinToString(",") { it.name },
            tMs = tMs
        )
    }
}
