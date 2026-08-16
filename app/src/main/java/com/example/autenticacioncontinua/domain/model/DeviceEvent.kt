package com.example.autenticacioncontinua.domain.model

/**
 * Sucesos que merecen quedar registrados en el dispositivo.
 *
 * El criterio para añadir uno: ¿me habría ahorrado una sesión de depuración a
 * ciegas? Si la respuesta es no, no entra — un diario que lo registra todo se
 * vuelve tan ilegible como no tener ninguno, y encima escribe en la misma base
 * que los sensores.
 */
enum class DeviceEventType {
    /** El servicio de recolección arrancó. */
    SERVICE_STARTED,

    /** El servicio se está destruyendo (el sistema lo mató, o se paró solo). */
    SERVICE_STOPPED,

    /** El vigía de WorkManager encontró el servicio caído y lo revivió.
     *  Si esto aparece a menudo, el dispositivo no tiene las exenciones. */
    SERVICE_REVIVED,

    /** Empezó una ráfaga de grabación. */
    BOUT_STARTED,

    /** Terminó una ráfaga. El detalle dice por qué y cuánto duró. */
    BOUT_FINISHED,

    /** Se cumplieron los 25 s de pantalla encendida pero NO había movimiento
     *  suficiente. Es la métrica que dice si el umbral está bien puesto. */
    GATE_REJECTED,

    /** Se agotó el presupuesto diario. */
    DAILY_LIMIT,

    /** Arrancó una sesión federada. */
    FL_STARTED,

    /** Terminó una sesión federada, con o sin medición final. */
    FL_FINISHED,

    /** La sesión federada abortó. El detalle lleva el mensaje de la excepción:
     *  es lo que identificó `UNAVAILABLE: End of stream or IOException` como
     *  caída del SERVIDOR y no de los teléfonos. */
    FL_FAILED,

    /** Estado de las exenciones al arrancar. Deja constancia de si el
     *  dispositivo estaba protegido cuando se recogieron los datos. */
    PROTECTION_STATUS,

    /** Empezó una captura etiquetada. El detalle lleva el seudónimo y si es
     *  ráfaga de control del dueño. Entra en el diario porque un tramo mal
     *  etiquetado contamina el conjunto genuino y hay que poder auditar
     *  después, sobre el propio teléfono, qué se capturó y cuándo. */
    LABELED_CAPTURE_STARTED,

    /** Terminó una captura etiquetada. */
    LABELED_CAPTURE_FINISHED,

    /** Tipo desconocido: lo escribió una versión posterior de la app. */
    UNKNOWN;

    companion object {
        fun fromStorage(value: String): DeviceEventType =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class DeviceEvent(
    val id: Long = 0,
    val timestampMs: Long,
    val type: DeviceEventType,
    val detail: String
)
