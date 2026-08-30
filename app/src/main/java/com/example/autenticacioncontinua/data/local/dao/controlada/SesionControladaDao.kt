package com.example.autenticacioncontinua.data.local.dao.controlada

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.controlada.CovariableSesionEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity

/** Recuento de sesiones utilizables por participante: mide el avance del estudio. */
data class AvanceParticipante(val participanteId: Long, val seudonimo: String, val sesiones: Int)

@Dao
interface SesionControladaDao {

    @Insert
    suspend fun insertar(sesion: SesionControladaEntity): Long

    @Query("SELECT * FROM sesiones_controladas WHERE id = :id")
    suspend fun porId(id: Long): SesionControladaEntity?

    @Query("SELECT * FROM sesiones_controladas ORDER BY inicioMs DESC")
    suspend fun todas(): List<SesionControladaEntity>

    @Query(
        "SELECT * FROM sesiones_controladas WHERE participanteId = :participanteId " +
            "ORDER BY inicioMs DESC"
    )
    suspend fun de(participanteId: Long): List<SesionControladaEntity>

    /**
     * Sesiones que quedaron en EN_CURSO.
     *
     * Existen porque la aplicación puede morir a mitad de una visita. Al
     * arrancar hay que cerrarlas como ABORTADAS: dejarlas abiertas haría que la
     * siguiente sesión del mismo participante pareciera una continuación de la
     * anterior, y los bloques de dos visitas distintas acabarían en el mismo
     * episodio.
     */
    @Query("SELECT * FROM sesiones_controladas WHERE estado = 'EN_CURSO'")
    suspend fun abiertas(): List<SesionControladaEntity>

    @Query(
        "UPDATE sesiones_controladas SET finMs = :finMs, estado = :estado, " +
            "bateriaFin = :bateriaFin WHERE id = :id"
    )
    suspend fun cerrar(id: Long, finMs: Long, estado: String, bateriaFin: Float?)

    @Query(
        "UPDATE sesiones_controladas SET estado = :estado, motivoInvalidacion = :motivo " +
            "WHERE id = :id"
    )
    suspend fun marcar(id: Long, estado: String, motivo: String)

    /** Cuántas visitas lleva ya el participante: fija el orden de dispositivo. */
    @Query("SELECT COUNT(*) FROM sesiones_controladas WHERE participanteId = :participanteId")
    suspend fun cuantasDe(participanteId: Long): Int

    @Query(
        "SELECT s.participanteId AS participanteId, p.seudonimo AS seudonimo, " +
            "COUNT(*) AS sesiones FROM sesiones_controladas s " +
            "JOIN participantes p ON p.id = s.participanteId " +
            "WHERE s.estado IN ('COMPLETA', 'ABORTADA') " +
            "GROUP BY s.participanteId, p.seudonimo ORDER BY p.seudonimo"
    )
    suspend fun avance(): List<AvanceParticipante>

    /**
     * Reparto de sesiones utilizables por dispositivo.
     *
     * Es la comprobación del contrabalanceo: si un participante acabó con siete
     * visitas en el terminal A y tres en el B, el efecto de persona y el de
     * dispositivo vuelven a estar confundidos, que es precisamente lo que este
     * diseño existía para evitar.
     */
    @Query(
        "SELECT dispositivoId, COUNT(*) AS n FROM sesiones_controladas " +
            "WHERE participanteId = :participanteId AND estado IN ('COMPLETA', 'ABORTADA') " +
            "GROUP BY dispositivoId"
    )
    suspend fun repartoPorDispositivo(participanteId: Long): List<RepartoDispositivo>

    @Insert
    suspend fun insertarCovariables(filas: List<CovariableSesionEntity>)

    @Query("SELECT * FROM covariables_sesion WHERE sesionId = :sesionId ORDER BY tMs")
    suspend fun covariablesDe(sesionId: Long): List<CovariableSesionEntity>

    companion object {
        val ESTADOS_UTILIZABLES = listOf(EstadoSesion.COMPLETA.name, EstadoSesion.ABORTADA.name)
    }
}

data class RepartoDispositivo(val dispositivoId: String, val n: Int)
