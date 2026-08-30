package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity

@Dao
interface MedicionLatenciaDao {

    @Insert
    suspend fun insertar(medicion: MedicionLatenciaEntity): Long

    @Insert
    suspend fun insertarTodas(mediciones: List<MedicionLatenciaEntity>)

    @Query("SELECT * FROM mediciones_latencia ORDER BY tMs DESC")
    suspend fun todas(): List<MedicionLatenciaEntity>

    @Query(
        "SELECT * FROM mediciones_latencia " +
            "WHERE etiqueta = :etiqueta AND configSensores = :config " +
            "ORDER BY tMs DESC"
    )
    suspend fun de(etiqueta: String, config: String): List<MedicionLatenciaEntity>

    /**
     * Mediana de las medianas por celda.
     *
     * No se promedian las medias: cada fila ya es un resumen de una serie, y
     * promediar medias de series de tamaño distinto pondera mal. Se toma la
     * mediana de las medianas, que es lo que se reporta.
     */
    @Query(
        "SELECT AVG(medianaMs) FROM mediciones_latencia " +
            "WHERE etiqueta = :etiqueta AND configSensores = :config"
    )
    suspend fun medianaTipica(etiqueta: String, config: String): Double?

    @Query("DELETE FROM mediciones_latencia")
    suspend fun borrarTodo()
}
