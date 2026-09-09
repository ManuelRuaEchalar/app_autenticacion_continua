package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.GyroscopeEntity

@Dao
interface GyroscopeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<GyroscopeEntity>)

    @Query("SELECT * FROM gyroscope_data WHERE dateString = :dateString ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getByDate(dateString: String, limit: Int, offset: Int): List<GyroscopeEntity>

    @Query("SELECT DISTINCT dateString FROM gyroscope_data ORDER BY dateString DESC")
    suspend fun getRecordedDates(): List<String>

    @Query("SELECT COUNT(*) FROM gyroscope_data WHERE dateString = :dateString")
    suspend fun getCountByDate(dateString: String): Int

    @Query("SELECT * FROM gyroscope_data ORDER BY timestamp ASC")
    suspend fun getAll(): List<GyroscopeEntity>



    /**
     * Cuantas lecturas hay a partir de `sinceMs`.
     *
     * Sirve para dimensionar de una vez los arrays de `SerieTriaxial` en lugar
     * de dejarlos duplicar su capacidad seis o siete veces: cada duplicacion
     * copia el array entero, y con millones de muestras esas copias son el pico
     * de memoria que hay que evitar.
     */
    @Query("SELECT COUNT(*) FROM gyroscope_data WHERE timestamp >= :sinceMs")
    suspend fun contarDesde(sinceMs: Long): Int

    /**
     * El siguiente bloque de lecturas a partir de `sinceMs`, en orden.
     *
     * PAGINACION POR CLAVE, NO POR `OFFSET`. `LIMIT :limite OFFSET n` obliga a
     * SQLite a recorrer y descartar las n primeras filas en cada bloque, de
     * modo que leer la tabla entera por bloques cuesta el cuadrado de leerla
     * de una vez. Aqui el bloque siguiente se pide diciendo por donde se quedo
     * el anterior, y con el indice de `timestamp` cada peticion continua el
     * recorrido donde lo dejo.
     *
     * EL DESEMPATE POR `id` ES NECESARIO. Varias muestras comparten
     * milisegundo: a 50 Hz no, pero las rafagas del sensor llegan agrupadas y
     * el reloj se repite. Paginando solo por `timestamp` habria que elegir
     * entre `>` —que se salta el resto de las muestras de ese milisegundo— y
     * `>=` —que las repite para siempre—. El par (timestamp, id) es unico y no
     * tiene ninguno de los dos problemas.
     *
     * Devuelve una lista vacia cuando ya no queda nada: esa es la condicion de
     * parada del bucle que lo consume.
     */
    @Query(
        "SELECT * FROM gyroscope_data " +
            "WHERE timestamp >= :sinceMs " +
            "AND (timestamp > :ultimoTs OR (timestamp = :ultimoTs AND id > :ultimoId)) " +
            "ORDER BY timestamp ASC, id ASC " +
            "LIMIT :limite"
    )
    suspend fun getBloqueDesde(
        sinceMs: Long,
        ultimoTs: Long,
        ultimoId: Long,
        limite: Int
    ): List<GyroscopeEntity>

    /** Ver [AccelerometerDao.deleteOlderThan]. */
    @Query("DELETE FROM gyroscope_data WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
