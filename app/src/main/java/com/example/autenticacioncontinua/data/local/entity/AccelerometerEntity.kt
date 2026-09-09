package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.AccelerometerData

/**
 * Una lectura del sensor.
 *
 * EL INDICE POR `timestamp` NO ES UN ADORNO. El ventaneo lee siempre por
 * ventana temporal (`WHERE timestamp >= :desde ORDER BY timestamp`), y la
 * purga borra por el mismo criterio. Sin indice, SQLite recorria la tabla
 * entera y ordenaba el resultado en un b-tree temporal: con 1,4 millones de
 * filas de dos semanas eso tardaba minutos y, sobre todo, obligaba a
 * materializar el resultado de golpe. Con indice, la lectura por bloques de
 * `SerieTriaxial` avanza en orden sin ordenar nada.
 */
@Entity(
    tableName = "accelerometer_data",
    indices = [Index(value = ["timestamp"])]
)
data class AccelerometerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long,
    val dateString: String
)

fun AccelerometerEntity.toDomain() = AccelerometerData(id, x, y, z, timestamp, dateString)
fun AccelerometerData.toEntity() = AccelerometerEntity(x = x, y = y, z = z, timestamp = timestamp, dateString = dateString)
