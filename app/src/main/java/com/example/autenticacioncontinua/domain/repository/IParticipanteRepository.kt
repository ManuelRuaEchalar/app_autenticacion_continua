package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.model.controlada.Participante

/** Un alta que no se pudo hacer, y por qué. */
sealed interface ResultadoAlta {
    data class Creado(val participante: Participante) : ResultadoAlta
    /** Ya existe alguien con ese seudónimo. Se devuelve para poder ofrecerlo. */
    data class SeudonimoDuplicado(val existente: Participante) : ResultadoAlta
    data class SeudonimoInvalido(val motivo: String) : ResultadoAlta
}

/**
 * Alta y consulta de participantes del estudio controlado.
 *
 * EL DUPLICADO SE DEVUELVE, NO SE LANZA. Un seudónimo repetido no es un fallo
 * del programa sino una situación normal de la mesa de trabajo: el
 * investigador vuelve a dar de alta a alguien que ya vino. Lo correcto es
 * enseñarle el participante existente y ofrecerle seleccionarlo, no reventar
 * con una excepción. Lo que NUNCA se hace es dejar pasar el alta: dos filas
 * para la misma persona la partirían en dos identidades, y en un análisis con
 * partición disjunta por persona eso es la misma persona a los dos lados de la
 * partición.
 */
interface IParticipanteRepository {

    /**
     * Da de alta a un participante. El seudónimo es lo ÚNICO que se pide.
     *
     * No hay tramo de edad, sexo, lateralidad ni competencia en latín: se
     * eliminaron el 30/08, de la tabla y no sólo del formulario. Ver la nota de
     * [com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity].
     */
    suspend fun alta(seudonimo: String): ResultadoAlta

    /** Todos, con su recuento de sesiones utilizables. */
    suspend fun todos(): List<Participante>

    suspend fun porId(id: Long): Participante?

    suspend fun porSeudonimo(seudonimo: String): Participante?

    suspend fun buscar(texto: String): List<Participante>

    /**
     * Borra al participante y todo lo suyo, en cascada.
     *
     * Sólo para deshacer un alta equivocada. Una sesión que salió mal se
     * INVALIDA con su motivo; no se borra.
     */
    suspend fun borrar(id: Long)
}
