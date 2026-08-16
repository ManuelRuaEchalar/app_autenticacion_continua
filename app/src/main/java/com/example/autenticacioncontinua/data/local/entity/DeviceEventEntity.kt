package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.DeviceEvent
import com.example.autenticacioncontinua.domain.model.DeviceEventType

/**
 * Una línea del diario de a bordo del dispositivo.
 *
 * Existe porque de los móviles de los participantes no se sabe NADA. El
 * 2026-08-15 dos teléfonos se cayeron a mitad de una sesión federada y hubo
 * que deducir lo ocurrido desde el `netstat` del servidor y desde un ping;
 * en los móviles ajenos ni eso. Un `Log.d` no sirve: cuando el participante
 * te enseña el teléfono, el logcat de hace tres horas ya no existe.
 *
 * Es deliberadamente pobre en datos: tipo, marca de tiempo y un detalle en
 * texto. No guarda nada del contenido de los sensores.
 */
@Entity(
    tableName = "device_events",
    // Debe coincidir EXACTAMENTE con el índice que crea AppDatabase
    // .MIGRATION_5_6: Room valida el esquema al abrir la base y un índice
    // declarado en un sitio y no en el otro aborta el arranque de la app.
    indices = [Index(value = ["timestampMs"])]
)
data class DeviceEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampMs: Long,
    /** Nombre de [DeviceEventType]. Se guarda como texto para que una
     *  versión vieja de la app no reviente al leer un tipo que no conoce. */
    val type: String,
    val detail: String
)

fun DeviceEventEntity.toDomain() = DeviceEvent(
    id = id,
    timestampMs = timestampMs,
    type = DeviceEventType.fromStorage(type),
    detail = detail
)
