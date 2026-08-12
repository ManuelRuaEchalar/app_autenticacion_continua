package com.example.autenticacioncontinua.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.AccelerometerEntity

@Dao
interface AccelerometerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<AccelerometerEntity>)

    @Query("SELECT * FROM accelerometer_data WHERE dateString = :dateString ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getByDate(dateString: String, limit: Int, offset: Int): List<AccelerometerEntity>

    @Query("SELECT * FROM accelerometer_data ORDER BY timestamp ASC")
    suspend fun getAll(): List<AccelerometerEntity>

    /**
     * Lecturas a partir de un instante. El ventaneo para entrenamiento usa
     * esta consulta en lugar de [getAll] para acotar la memoria: a 50 Hz, un
     * histórico de meses no cabe holgadamente en el heap de una app de
     * usuario junto al intérprete TFLite.
     */
    @Query("SELECT * FROM accelerometer_data WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getSince(sinceMs: Long): List<AccelerometerEntity>

    /**
     * Borra lo que ya no puede usarse.
     *
     * `WindowSegmenter` sólo mira los últimos `DEFAULT_HISTORY_MS` (14 días),
     * así que las filas anteriores no se leen nunca y sólo engordan la base.
     * Sin esta purga la BD crece sin techo: a 50 Hz y dos sensores son ~360
     * mil filas por hora de grabación.
     *
     * @return filas borradas.
     */
    @Query("DELETE FROM accelerometer_data WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
