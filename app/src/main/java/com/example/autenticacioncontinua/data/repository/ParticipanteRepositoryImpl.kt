package com.example.autenticacioncontinua.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity
import com.example.autenticacioncontinua.domain.model.controlada.Participante
import com.example.autenticacioncontinua.domain.repository.IParticipanteRepository
import com.example.autenticacioncontinua.domain.repository.ResultadoAlta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParticipanteRepositoryImpl(
    private val db: AppDatabase
) : IParticipanteRepository {

    override suspend fun alta(
        seudonimo: String,
        tramoEdad: String,
        sexo: String,
        lateralidad: String,
        competenciaLatin: String,
        notas: String
    ): ResultadoAlta = withContext(Dispatchers.IO) {
        val canonico = ParticipanteEntity.normalizar(seudonimo)
        validar(canonico)?.let { return@withContext ResultadoAlta.SeudonimoInvalido(it) }

        // Se consulta primero para poder devolver el existente, pero la garantía
        // real es el índice único: entre esta consulta y la inserción cabe otra
        // alta, y es la base la que tiene que decir que no.
        db.participanteDao().porSeudonimo(canonico)?.let {
            return@withContext ResultadoAlta.SeudonimoDuplicado(it.aDominio())
        }

        val entidad = ParticipanteEntity(
            seudonimo = canonico,
            fechaAltaMs = System.currentTimeMillis(),
            tramoEdad = tramoEdad,
            sexo = sexo,
            lateralidad = lateralidad,
            competenciaLatin = competenciaLatin,
            notas = notas.trim()
        )
        try {
            val id = db.participanteDao().insertar(entidad)
            ResultadoAlta.Creado(entidad.copy(id = id).aDominio())
        } catch (e: SQLiteConstraintException) {
            // Perdió la carrera contra otra alta simultánea. El existente es el
            // bueno; este alta no se rehace.
            val existente = db.participanteDao().porSeudonimo(canonico)
            if (existente != null) ResultadoAlta.SeudonimoDuplicado(existente.aDominio())
            else throw e
        }
    }

    override suspend fun todos(): List<Participante> = withContext(Dispatchers.IO) {
        val avance = db.sesionControladaDao().avance().associateBy { it.participanteId }
        db.participanteDao().todos().map { it.aDominio(avance[it.id]?.sesiones ?: 0) }
    }

    override suspend fun porId(id: Long): Participante? = withContext(Dispatchers.IO) {
        db.participanteDao().porId(id)?.let {
            it.aDominio(db.sesionControladaDao().cuantasDe(it.id))
        }
    }

    override suspend fun porSeudonimo(seudonimo: String): Participante? =
        withContext(Dispatchers.IO) {
            db.participanteDao().porSeudonimo(ParticipanteEntity.normalizar(seudonimo))
                ?.let { it.aDominio(db.sesionControladaDao().cuantasDe(it.id)) }
        }

    override suspend fun buscar(texto: String): List<Participante> = withContext(Dispatchers.IO) {
        db.participanteDao().buscar(ParticipanteEntity.normalizar(texto)).map { it.aDominio() }
    }

    override suspend fun borrar(id: Long) = withContext(Dispatchers.IO) {
        db.participanteDao().borrar(id)
    }

    private companion object {
        /**
         * Un seudónimo tiene que ser corto y sin espacios para poder anotarse en
         * el cuaderno de campo sin ambigüedad. La regla se aplica aquí, sobre la
         * forma ya normalizada, y no en la interfaz: la interfaz puede
         * cambiarse o duplicarse; el repositorio es por donde pasan todas las
         * altas.
         */
        val PATRON = Regex("^[A-Z0-9_-]{2,16}$")

        fun validar(canonico: String): String? = when {
            canonico.isEmpty() -> "el seudonimo no puede estar vacio"
            !PATRON.matches(canonico) ->
                "usa de 2 a 16 caracteres, solo letras, digitos, guion y guion bajo (p. ej. P01)"
            else -> null
        }
    }
}

private fun ParticipanteEntity.aDominio(sesionesHechas: Int = 0) = Participante(
    id = id,
    seudonimo = seudonimo,
    fechaAltaMs = fechaAltaMs,
    tramoEdad = tramoEdad,
    sexo = sexo,
    lateralidad = lateralidad,
    competenciaLatin = competenciaLatin,
    notas = notas,
    sesionesHechas = sesionesHechas
)
