package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.LabeledSessionEntity
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.domain.model.LabeledSession
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LabeledSessionRepositoryImpl(
    private val db: AppDatabase
) : ILabeledSessionRepository {

    /**
     * NO lleva `runCatching`, al revés que el diario de eventos.
     *
     * El diario puede perderse sin consecuencias. Esto no: si falla la
     * anotación y la captura siguiese adelante, quedarían tres minutos de datos
     * de otra persona indistinguibles de los del dueño dentro del histórico de
     * entrenamiento. Que la excepción suba y aborte la captura es el
     * comportamiento correcto.
     */
    override suspend fun abrir(
        participantId: String,
        isOwner: Boolean,
        note: String
    ): Long = withContext(Dispatchers.IO) {
        db.labeledSessionDao().insert(
            LabeledSessionEntity(
                participantId = participantId.trim(),
                startMs = System.currentTimeMillis(),
                endMs = 0L,
                isOwner = isOwner,
                note = note.trim()
            )
        )
    }

    override suspend fun cerrar(id: Long, endMs: Long) = withContext(Dispatchers.IO) {
        db.labeledSessionDao().close(id, endMs)
    }

    override suspend fun todas(): List<LabeledSession> = withContext(Dispatchers.IO) {
        db.labeledSessionDao().getAll().map { it.toDomain() }
    }

    override suspend fun desde(desdeMs: Long): List<LabeledSession> = withContext(Dispatchers.IO) {
        // Se pide desde antes del corte para no perder un tramo que empezó
        // justo antes de la ventana de histórico y sigue solapándola.
        val margen = desdeMs - LabeledSession.MAX_ABIERTA_MS
        db.labeledSessionDao().getSince(margen).map { it.toDomain() }
    }

    override suspend fun borrar(id: Long) = withContext(Dispatchers.IO) {
        db.labeledSessionDao().delete(id)
    }
}
