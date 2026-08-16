package com.example.autenticacioncontinua.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.domain.model.LabeledSession
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.session.ISessionManager
import com.example.autenticacioncontinua.domain.session.LabeledCaptureFase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resumen por participante, que es lo que el investigador necesita mirar
 * durante la sesión: con cuatro impostores a cuatro ráfagas cada uno hay
 * dieciséis capturas que llevar, y sin este recuento no hay forma de saber a
 * quién le faltan.
 */
data class ResumenParticipante(
    val participantId: String,
    val isOwner: Boolean,
    val rafagas: Int,
    val minutosTotales: Int
)

data class LabeledCaptureUiState(
    val participantId: String = "",
    val isOwner: Boolean = false,
    val note: String = "",
    val fase: LabeledCaptureFase? = null,
    val enCurso: Boolean = false,
    val resumen: List<ResumenParticipante> = emptyList(),
    val ultimas: List<LabeledSession> = emptyList()
)

class LabeledCaptureViewModel(
    private val sessionManager: ISessionManager,
    private val labeledSessions: ILabeledSessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LabeledCaptureUiState())
    val uiState: StateFlow<LabeledCaptureUiState> = _uiState.asStateFlow()

    init {
        refrescar()
    }

    fun setParticipantId(v: String) {
        _uiState.value = _uiState.value.copy(participantId = v)
    }

    fun setIsOwner(v: Boolean) {
        _uiState.value = _uiState.value.copy(isOwner = v)
    }

    fun setNote(v: String) {
        _uiState.value = _uiState.value.copy(note = v)
    }

    fun capturar() {
        if (_uiState.value.enCurso) return
        val s = _uiState.value
        _uiState.value = s.copy(enCurso = true, fase = null)

        viewModelScope.launch {
            sessionManager.startLabeledCapture(
                participantId = s.participantId,
                isOwner = s.isOwner,
                note = s.note
            ) { fase ->
                _uiState.value = _uiState.value.copy(fase = fase)
            }
            // Se llega aquí con la captura ya cerrada, completa o no. El
            // identificador y la marca de "dueño" NO se limpian: lo normal es
            // encadenar varias ráfagas del mismo participante, y volver a
            // teclearlas cada vez es la forma más fácil de acabar con dos
            // seudónimos distintos para la misma persona.
            _uiState.value = _uiState.value.copy(enCurso = false, note = "")
            refrescar()
        }
    }

    fun cancelar() {
        sessionManager.cancelLabeledCapture()
    }

    fun refrescar() {
        viewModelScope.launch {
            val todas = labeledSessions.todas()
            val resumen = todas
                .groupBy { it.participantId }
                .map { (id, filas) ->
                    ResumenParticipante(
                        participantId = id,
                        isOwner = filas.any { it.isOwner },
                        rafagas = filas.size,
                        minutosTotales =
                            (filas.filterNot { it.enCurso }.sumOf { it.durationMs } / 60_000).toInt()
                    )
                }
                // El dueño abajo: los impostores son lo que hay que ir
                // completando, y el control se hace un par de veces.
                .sortedWith(compareBy({ it.isOwner }, { it.participantId }))

            _uiState.value = _uiState.value.copy(
                resumen = resumen,
                ultimas = todas.take(10)
            )
        }
    }
}
