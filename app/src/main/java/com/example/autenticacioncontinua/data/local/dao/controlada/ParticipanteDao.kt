package com.example.autenticacioncontinua.data.local.dao.controlada

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity

@Dao
interface ParticipanteDao {

    /**
     * `ABORT` a propósito, no `REPLACE` ni `IGNORE`.
     *
     * Un seudónimo repetido significa que el investigador se equivocó al dar de
     * alta, y las tres salidas posibles no son equivalentes: `REPLACE` borraría
     * en cascada las sesiones del participante existente —que es el peor
     * resultado imaginable—, `IGNORE` devolvería -1 y dejaría continuar como si
     * nada, y `ABORT` lanza y obliga a la interfaz a decirlo. La excepción se
     * traduce a un mensaje en el repositorio.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertar(participante: ParticipanteEntity): Long

    @Update
    suspend fun actualizar(participante: ParticipanteEntity)

    @Query("SELECT * FROM participantes ORDER BY seudonimo")
    suspend fun todos(): List<ParticipanteEntity>

    @Query("SELECT * FROM participantes WHERE id = :id")
    suspend fun porId(id: Long): ParticipanteEntity?

    @Query("SELECT * FROM participantes WHERE seudonimo = :seudonimo")
    suspend fun porSeudonimo(seudonimo: String): ParticipanteEntity?

    @Query("SELECT * FROM participantes WHERE seudonimo LIKE '%' || :texto || '%' ORDER BY seudonimo")
    suspend fun buscar(texto: String): List<ParticipanteEntity>

    @Query("SELECT COUNT(*) FROM participantes")
    suspend fun cuantos(): Int

    /**
     * Borra al participante y, en cascada, sus sesiones, bloques, muestras y
     * eventos. Sólo para deshacer un alta equivocada: una sesión que salió mal
     * se INVALIDA, no se borra.
     */
    @Query("DELETE FROM participantes WHERE id = :id")
    suspend fun borrar(id: Long)
}
