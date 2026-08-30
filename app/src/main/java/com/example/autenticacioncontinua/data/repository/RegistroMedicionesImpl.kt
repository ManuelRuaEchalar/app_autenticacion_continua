package com.example.autenticacioncontinua.data.repository

import android.util.Log
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity
import com.example.autenticacioncontinua.domain.repository.IRegistroMediciones
import com.example.autenticacioncontinua.monitoring.EstadisticaLatencia
import com.example.autenticacioncontinua.monitoring.MetodoConsumo
import com.example.autenticacioncontinua.monitoring.ResumenRecursos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RegistroMedicionesImpl(
    private val db: AppDatabase
) : IRegistroMediciones {

    override suspend fun registrarBloque(
        resumen: ResumenRecursos,
        tipoOperacion: String,
        configSensores: String,
        regimenAprendizaje: String
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            db.medicionRecursosDao().insertar(
                MedicionRecursosEntity.desde(
                    resumen, tipoOperacion, configSensores, regimenAprendizaje
                )
            )
        }.onFailure {
            // Se avisa pero no se propaga: medir no puede tumbar lo medido.
            Log.w(TAG, "no se pudo guardar el bloque '${resumen.etiqueta}'", it)
        }.getOrNull()
    }

    override suspend fun registrarLatencia(
        estadistica: EstadisticaLatencia,
        configSensores: String,
        regimenAprendizaje: String
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            db.medicionLatenciaDao().insertar(
                MedicionLatenciaEntity.desde(estadistica, configSensores, regimenAprendizaje)
            )
        }.onFailure {
            Log.w(TAG, "no se pudo guardar la latencia '${estadistica.etiqueta}'", it)
        }.getOrNull()
    }

    override suspend fun bloques(): List<MedicionRecursosEntity> = withContext(Dispatchers.IO) {
        db.medicionRecursosDao().todas()
    }

    override suspend fun bloquesValidos(): List<MedicionRecursosEntity> =
        withContext(Dispatchers.IO) { db.medicionRecursosDao().validas() }

    override suspend fun latencias(): List<MedicionLatenciaEntity> = withContext(Dispatchers.IO) {
        db.medicionLatenciaDao().todas()
    }

    override suspend fun consumoMedioPorHora(
        configSensores: String,
        regimenAprendizaje: String,
        metodo: MetodoConsumo
    ): Double? = withContext(Dispatchers.IO) {
        db.medicionRecursosDao()
            .consumoMedioPorHora(configSensores, regimenAprendizaje, metodo.name)
    }

    override suspend fun bloquesPorMetodo(): Map<MetodoConsumo, Int> =
        withContext(Dispatchers.IO) {
            db.medicionRecursosDao().recuentoPorMetodo().mapNotNull { fila ->
                runCatching { MetodoConsumo.valueOf(fila.metodo) }.getOrNull()
                    ?.let { it to fila.n }
            }.toMap()
        }

    override suspend fun bloquesValidosEn(
        configSensores: String,
        regimenAprendizaje: String
    ): Int = withContext(Dispatchers.IO) {
        db.medicionRecursosDao().cuantasValidas(configSensores, regimenAprendizaje)
    }

    override suspend fun descartesPorMotivo(): Map<String, Int> = withContext(Dispatchers.IO) {
        db.medicionRecursosDao().recuentoDeDescartes().associate { it.motivo to it.n }
    }

    private companion object {
        const val TAG = "RegistroMediciones"
    }
}
