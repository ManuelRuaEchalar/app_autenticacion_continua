package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** En qué estado quedó una sesión. */
enum class EstadoSesion {
    EN_CURSO,
    COMPLETA,

    /**
     * La aplicación murió, el participante se marchó, se agotó la batería. Los
     * bloques que sí se completaron se conservan y son utilizables.
     */
    ABORTADA,

    /**
     * Salió mal por un motivo que la invalida: participante equivocado,
     * interrupción, teclado sin configurar.
     *
     * NO SE BORRA, SE MARCA. Borrar destruye la trazabilidad —el recuento de
     * sesiones dejaría de cuadrar con el cuaderno de campo— y, sobre todo,
     * invita a borrar lo que no gusta. Una sesión invalidada con su motivo
     * escrito es un dato del estudio; una sesión desaparecida es un agujero
     * que nadie puede auditar.
     */
    INVALIDADA
}

/**
 * Una visita de un participante: aclimatación más tres bloques de tecleo.
 *
 * UN SOLO DISPOSITIVO POR VISITA, ALTERNADOS (decisión del 23/08). [ordenDispositivo]
 * guarda el número de orden de esta visita dentro de la secuencia A, B, A, B…
 * del participante. Es lo que después permite comprobar que el reparto quedó
 * equilibrado y, si no lo quedó, ajustar el modelo por él en vez de descubrir
 * el desequilibrio cuando ya no hay remedio.
 *
 * [semillaSeleccion] fija la elección de párrafos e idiomas de la sesión. Con
 * ella, la secuencia exacta de textos que vio un participante se puede
 * reconstruir meses después sin haberla guardado entera.
 *
 * [versionApp] y [versionProtocolo] van en cada fila y no en un fichero de
 * configuración porque la recogida dura 60 días y la aplicación va a cambiar
 * durante ellos. Sin esta columna, un cambio de tasa de muestreo a mitad del
 * estudio sería indistinguible de un efecto de los datos.
 */
@Entity(
    tableName = "sesiones_controladas",
    foreignKeys = [
        ForeignKey(
            entity = ParticipanteEntity::class,
            parentColumns = ["id"],
            childColumns = ["participanteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["participanteId"]), Index(value = ["inicioMs"])]
)
data class SesionControladaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val participanteId: Long,

    /** Identificador estable del terminal (`A` o `B`), no el modelo. */
    val dispositivoId: String,

    val inicioMs: Long,
    /** 0 mientras la sesión sigue abierta. */
    val finMs: Long = 0,

    /** Número de visita del participante: 1, 2, 3… */
    val ordenDispositivo: Int,

    val semillaSeleccion: Long,

    val versionApp: String,
    val versionProtocolo: String,

    /**
     * Porcentaje de batería al abrir y al cerrar.
     *
     * No es la medida de consumo —para eso está `mediciones_recursos`, que usa
     * el contador de carga— sino una covariable de sesión: la tasa de descarga
     * del litio depende del nivel de carga, y hay que poder comprobar que las
     * sesiones no se agruparon sistemáticamente en un extremo de la curva.
     */
    val bateriaInicio: Float?,
    val bateriaFin: Float? = null,

    val estado: String = EstadoSesion.EN_CURSO.name,

    val motivoInvalidacion: String = ""
) {
    val estaAbierta: Boolean get() = estado == EstadoSesion.EN_CURSO.name

    /** Utilizable en el análisis: completa o abortada con bloques buenos. */
    val esUtilizable: Boolean
        get() = estado == EstadoSesion.COMPLETA.name || estado == EstadoSesion.ABORTADA.name
}
