package com.example.autenticacioncontinua.monitoring

import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity
import com.example.autenticacioncontinua.domain.repository.IRegistroMediciones

/**
 * Dobles compartidos por las pruebas del módulo de medición.
 *
 * Están aquí y no repetidos en cada fichero de prueba porque los tres —fuente
 * de energía, fuente de memoria y registro— se usan igual en todas ellas, y
 * tres copias divergentes de un doble acaban probando tres cosas distintas.
 */

/**
 * Batería falsa con descarga lineal.
 *
 * @param cargaInicialMicroAh `null` simula un terminal que no expone
 *   `BATTERY_PROPERTY_CHARGE_COUNTER`, que es un caso real y hay que probarlo.
 * @param descargaPorLectura cuánto baja el contador en cada lectura. A cero,
 *   simula un bloque demasiado corto para la resolución del aparato.
 */
class FuenteEnergiaFalsa(
    private val cargaInicialMicroAh: Long? = 3_000_000L,
    private val descargaPorLectura: Long = 100L,
    var corriente: Long? = -250_000L,
    var cargando: Boolean = false,
    var porcentajeActual: Float? = 50f
) : FuenteEnergia {

    var lecturas = 0
        private set

    override fun cargaMicroAh(): Long? {
        val base = cargaInicialMicroAh ?: return null.also { lecturas++ }
        return (base - descargaPorLectura * lecturas).also { lecturas++ }
    }

    override fun corrienteMicroA(): Long? = corriente
    override fun porcentaje(): Float? = porcentajeActual
    override fun estaCargando(): Boolean = cargando
}

/** Memoria falsa que recorre una secuencia de valores y se queda en el último. */
class FuenteMemoriaFalsa(
    private val secuenciaKb: List<Long> = listOf(150_000L)
) : FuenteMemoria {
    private var i = 0
    override fun pssProcesoKb(): Long =
        secuenciaKb[minOf(i++, secuenciaKb.size - 1)]
}

/** Registro en memoria: guarda lo que se le pide y deja inspeccionarlo. */
class RegistroEnMemoria : IRegistroMediciones {

    val bloques = mutableListOf<MedicionRecursosEntity>()
    val latenciasGuardadas = mutableListOf<MedicionLatenciaEntity>()

    /** Si se activa, `registrarBloque` falla: simula la base llena o cerrada. */
    var fallaAlGuardar = false

    override suspend fun registrarBloque(
        resumen: ResumenRecursos,
        tipoOperacion: String,
        configSensores: String,
        regimenAprendizaje: String
    ): Long? {
        if (fallaAlGuardar) return null
        val fila = MedicionRecursosEntity.desde(
            resumen, tipoOperacion, configSensores, regimenAprendizaje,
            tMs = bloques.size.toLong()
        )
        bloques += fila
        return bloques.size.toLong()
    }

    override suspend fun registrarLatencia(
        estadistica: EstadisticaLatencia,
        configSensores: String,
        regimenAprendizaje: String
    ): Long? {
        latenciasGuardadas += MedicionLatenciaEntity.desde(
            estadistica, configSensores, regimenAprendizaje,
            tMs = latenciasGuardadas.size.toLong()
        )
        return latenciasGuardadas.size.toLong()
    }

    override suspend fun bloques(): List<MedicionRecursosEntity> = bloques
    override suspend fun bloquesValidos(): List<MedicionRecursosEntity> =
        bloques.filter { it.esValida }

    override suspend fun latencias(): List<MedicionLatenciaEntity> = latenciasGuardadas

    override suspend fun consumoMedioPorHora(
        configSensores: String,
        regimenAprendizaje: String,
        metodo: MetodoConsumo
    ): Double? = bloques
        .filter {
            it.esValida && it.configSensores == configSensores &&
                it.regimenAprendizaje == regimenAprendizaje &&
                it.metodoConsumo == metodo.name
        }
        .mapNotNull { it.tasaConsumoMicroAhPorHora }
        .takeIf { it.isNotEmpty() }
        ?.average()

    override suspend fun bloquesPorMetodo(): Map<MetodoConsumo, Int> =
        bloques.groupingBy { MetodoConsumo.valueOf(it.metodoConsumo) }.eachCount()

    override suspend fun bloquesValidosEn(
        configSensores: String,
        regimenAprendizaje: String
    ): Int = bloques.count {
        it.esValida && it.configSensores == configSensores &&
            it.regimenAprendizaje == regimenAprendizaje
    }

    override suspend fun descartesPorMotivo(): Map<String, Int> =
        bloques.filterNot { it.esValida }.groupingBy { it.invalidez }.eachCount()
}
