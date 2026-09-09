package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity
import com.example.autenticacioncontinua.monitoring.EstadisticaLatencia
import com.example.autenticacioncontinua.monitoring.EstadoPantalla
import com.example.autenticacioncontinua.monitoring.MetodoConsumo
import com.example.autenticacioncontinua.monitoring.ResumenRecursos

/**
 * Registro persistente de mediciones de coste.
 *
 * Es lo que da soporte a la Propuesta I: el coste marginal del componente
 * federado se estima comparando celdas (configuración de sensores × régimen de
 * aprendizaje), y para eso las mediciones tienen que sobrevivir al cierre de la
 * app y poder exportarse.
 *
 * A diferencia del diario de eventos, esto SÍ traga los errores: perder un
 * resumen de bloque es un dato menos en una tabla que tendrá cientos, mientras
 * que dejar que la excepción suba abortaría la ronda federada o la sesión
 * controlada que se estaba midiendo. La medición es el observador, no el
 * experimento; no debe poder tumbarlo.
 */
interface IRegistroMediciones {

    /**
     * Guarda el resumen de un bloque.
     *
     * Guarda TAMBIÉN los inválidos, con su motivo. Descartarlos aquí escondería
     * cuántas mediciones se perdieron y por qué, que es justo lo que hay que
     * poder reportar en la sección de limitaciones.
     *
     * @return el id asignado, o null si no se pudo guardar.
     */
    suspend fun registrarBloque(
        resumen: ResumenRecursos,
        tipoOperacion: String,
        configSensores: String,
        regimenAprendizaje: String
    ): Long?

    suspend fun registrarLatencia(
        estadistica: EstadisticaLatencia,
        configSensores: String,
        regimenAprendizaje: String,
        estadoPantalla: EstadoPantalla = EstadoPantalla.DESCONOCIDO
    ): Long?

    suspend fun bloques(): List<MedicionRecursosEntity>

    suspend fun bloquesValidos(): List<MedicionRecursosEntity>

    suspend fun latencias(): List<MedicionLatenciaEntity>

    /**
     * Consumo medio por hora de una celda del diseño medido con [metodo], o
     * null si en esa celda no hay ninguna medición válida por ese método.
     *
     * EL MÉTODO ES OBLIGATORIO. Promediar juntas una tasa sacada del contador
     * de carga y otra de integrar la corriente daría un número con unidades
     * correctas y sin significado: no miden lo mismo ni tienen el mismo sesgo.
     */
    suspend fun consumoMedioPorHora(
        configSensores: String,
        regimenAprendizaje: String,
        metodo: MetodoConsumo
    ): Double?

    /** Qué métodos de medición hay en la base y cuántos bloques de cada uno. */
    suspend fun bloquesPorMetodo(): Map<MetodoConsumo, Int>

    /** Cuántos bloques válidos hay en una celda: dice si falta muestreo. */
    suspend fun bloquesValidosEn(configSensores: String, regimenAprendizaje: String): Int

    /** Motivo de descarte -> número de bloques descartados por ese motivo. */
    suspend fun descartesPorMotivo(): Map<String, Int>
}
