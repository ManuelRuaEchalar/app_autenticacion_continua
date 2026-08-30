package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity

/**
 * Acceso a los resúmenes de bloque.
 *
 * Las consultas de agregación viven aquí y no en Kotlin porque el análisis las
 * necesita sobre miles de filas y SQLite las resuelve sin traerlas a memoria.
 * `esValida` no es una columna —es una propiedad calculada de la entidad— así
 * que el filtro por validez se escribe sobre `invalidez = ''`.
 */
@Dao
interface MedicionRecursosDao {

    @Insert
    suspend fun insertar(medicion: MedicionRecursosEntity): Long

    @Insert
    suspend fun insertarTodas(mediciones: List<MedicionRecursosEntity>)

    @Query("SELECT * FROM mediciones_recursos ORDER BY tMs DESC")
    suspend fun todas(): List<MedicionRecursosEntity>

    /** Sólo las que valen: las inválidas se conservan para auditoría, no para promediar. */
    @Query("SELECT * FROM mediciones_recursos WHERE valida = 1 ORDER BY tMs DESC")
    suspend fun validas(): List<MedicionRecursosEntity>

    @Query(
        "SELECT * FROM mediciones_recursos " +
            "WHERE configSensores = :config AND regimenAprendizaje = :regimen " +
            "AND valida = 1 ORDER BY tMs DESC"
    )
    suspend fun validasDe(config: String, regimen: String): List<MedicionRecursosEntity>

    /**
     * Tasa media de consumo de una celda del diseño.
     *
     * Se promedia SOLO dentro de un mismo metodo de medicion: mezclar una tasa
     * del contador de carga con otra integrada de la corriente daria un numero
     * con unidades correctas y sin significado. Devuelve null si esa celda no
     * tiene ninguna medicion por ese metodo — que es informacion, no un cero.
     */
    @Query(
        "SELECT AVG(tasaConsumoMicroAhPorHora) FROM mediciones_recursos " +
            "WHERE configSensores = :config AND regimenAprendizaje = :regimen " +
            "AND metodoConsumo = :metodo " +
            "AND valida = 1 AND tasaConsumoMicroAhPorHora IS NOT NULL"
    )
    suspend fun consumoMedioPorHora(config: String, regimen: String, metodo: String): Double?

    /** Que metodos de medicion hay en la tabla y cuantas filas de cada uno. */
    @Query(
        "SELECT metodoConsumo AS metodo, COUNT(*) AS n FROM mediciones_recursos " +
            "WHERE valida = 1 GROUP BY metodoConsumo ORDER BY n DESC"
    )
    suspend fun recuentoPorMetodo(): List<RecuentoMetodo>

    @Query(
        "SELECT COUNT(*) FROM mediciones_recursos " +
            "WHERE configSensores = :config AND regimenAprendizaje = :regimen AND valida = 1"
    )
    suspend fun cuantasValidas(config: String, regimen: String): Int

    /** Cuántas se descartaron y por qué: va en la sección de limitaciones. */
    @Query(
        "SELECT invalidez AS motivo, COUNT(*) AS n FROM mediciones_recursos " +
            "WHERE valida = 0 GROUP BY invalidez ORDER BY n DESC"
    )
    suspend fun recuentoDeDescartes(): List<RecuentoDescarte>

    @Query("DELETE FROM mediciones_recursos")
    suspend fun borrarTodo()
}

data class RecuentoDescarte(val motivo: String, val n: Int)

data class RecuentoMetodo(val metodo: String, val n: Int)
