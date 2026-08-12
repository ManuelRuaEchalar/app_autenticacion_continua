package com.example.autenticacioncontinua.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.domain.model.TrainingRun
import com.example.autenticacioncontinua.domain.repository.ITrainingHistoryRepository
import com.example.autenticacioncontinua.federated.FederatedLearningService
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.example.autenticacioncontinua.BuildConfig

/**
 * Las métricas son de VALIDACIÓN, no de test.
 *
 * El conjunto de test local no se toca durante la federación: es lo que hace
 * que el número final que se reporte en la tesis sea ciego. Etiquetarlas así
 * en la UI evita que alguien lea el EER de una ronda como si fuera el
 * resultado del sistema.
 */
data class FederatedUiState(
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val currentEer: Double? = null,
    val currentAuc: Double? = null,
    val calibratedThreshold: Float? = null,
    val serverHost: String = BuildConfig.FLOWER_HOST,
    val statusMessage: String = "Inactivo",

    /** Resultado de la última sesión terminada, sobre el conjunto CIEGO. */
    val lastRun: TrainingRun? = null,

    /** Historial completo, lo más reciente primero. */
    val history: List<TrainingRun> = emptyList()
)

class FederatedViewModel(application: Application) : AndroidViewModel(application) {

    private val trainingHistory: ITrainingHistoryRepository by inject(
        ITrainingHistoryRepository::class.java
    )

    private val _uiState = MutableStateFlow(FederatedUiState())
    val uiState: StateFlow<FederatedUiState> = _uiState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FederatedLearningService.ACTION_FL_PROGRESS -> {
                    val round = intent.getIntExtra(FederatedLearningService.EXTRA_ROUND, 0)
                    val eer = intent.getDoubleExtra(FederatedLearningService.EXTRA_EER, -1.0)
                    val auc = intent.getDoubleExtra(FederatedLearningService.EXTRA_AUC, -1.0)
                    val threshold =
                        intent.getFloatExtra(FederatedLearningService.EXTRA_THRESHOLD, -1f)
                    _uiState.update {
                        it.copy(
                            currentRound = round,
                            currentEer = eer,
                            currentAuc = auc,
                            calibratedThreshold = threshold,
                            statusMessage = "Ronda $round — validación: " +
                                "EER ${String.format("%.2f%%", eer * 100)}, " +
                                "AUC ${String.format("%.4f", auc)}, " +
                                "umbral ${String.format("%.3f", threshold)}"
                        )
                    }
                }
                FederatedLearningService.ACTION_FL_DONE -> {
                    // "Finalizado exitosamente" no le dice nada al usuario
                    // sobre cómo va su modelo. Se resume el resultado del
                    // conjunto CIEGO, que es el único informativo, y se avisa
                    // cuando el número no es de fiar.
                    val auc = intent.getDoubleExtra(
                        FederatedLearningService.EXTRA_TEST_AUC, -1.0
                    )
                    val eer = intent.getDoubleExtra(
                        FederatedLearningService.EXTRA_TEST_EER, -1.0
                    )
                    val frr = intent.getDoubleExtra(
                        FederatedLearningService.EXTRA_TEST_FRR, -1.0
                    )
                    val n = intent.getIntExtra(FederatedLearningService.EXTRA_TEST_N, 0)
                    val sesiones = intent.getIntExtra(
                        FederatedLearningService.EXTRA_SESSIONS, 0
                    )
                    val rondas = intent.getIntExtra(
                        FederatedLearningService.EXTRA_ROUNDS, 0
                    )

                    val resumen = when {
                        auc < 0 -> "Entrenamiento terminado en $rondas rondas, " +
                            "pero no se pudo medir sobre el conjunto de prueba."
                        else -> buildString {
                            append("Terminado en $rondas rondas — prueba ciega: ")
                            append("EER ${String.format("%.2f%%", eer * 100)}, ")
                            append("AUC ${String.format("%.4f", auc)} (n=$n)")
                            if (sesiones < TrainingRun.MIN_SESSIONS) {
                                append(". ⚠ Sólo $sesiones sesión(es) de uso: " +
                                    "el resultado está inflado, sigue usando el " +
                                    "móvil varios días")
                            }
                            if (frr >= 0.9) {
                                append(". ⚠ El umbral rechazaría casi siempre al " +
                                    "usuario legítimo")
                            }
                        }
                    }
                    _uiState.update { it.copy(isRunning = false, statusMessage = resumen) }
                    refreshHistory()
                }
                FederatedLearningService.ACTION_FL_ERROR -> {
                    val msg = intent.getStringExtra(FederatedLearningService.EXTRA_ERROR_MSG) ?: "Error"
                    _uiState.update { 
                        it.copy(
                            isRunning = false,
                            statusMessage = "Error: $msg"
                        ) 
                    }
                }
                FederatedLearningService.ACTION_FL_STATUS -> {
                    val msg = intent.getStringExtra(FederatedLearningService.EXTRA_STATUS_MSG) ?: ""
                    _uiState.update { it.copy(statusMessage = msg) }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(FederatedLearningService.ACTION_FL_PROGRESS)
            addAction(FederatedLearningService.ACTION_FL_DONE)
            addAction(FederatedLearningService.ACTION_FL_ERROR)
            addAction(FederatedLearningService.ACTION_FL_STATUS)
        }
        ContextCompat.registerReceiver(
            application,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // El historial sobrevive al cierre de la app: al volver, el usuario
        // tiene que poder ver cómo ha ido evolucionando su modelo sin haber
        // tenido que dejar la pantalla abierta.
        viewModelScope.launch {
            trainingHistory.observeRecent(HISTORY_LIMIT).collect { runs ->
                _uiState.update { it.copy(history = runs, lastRun = runs.firstOrNull()) }
            }
        }
    }

    private fun refreshHistory() {
        // `observeRecent` ya es reactivo; esto sólo cubre el instante entre el
        // broadcast y la emisión de Room.
        viewModelScope.launch {
            runCatching { trainingHistory.getLast() }
                .getOrNull()
                ?.let { last -> _uiState.update { it.copy(lastRun = last) } }
        }
    }

    fun updateHost(host: String) {
        _uiState.update { it.copy(serverHost = host) }
    }

    fun startFederatedLearning() {
        _uiState.update { 
            it.copy(
                isRunning = true,
                currentRound = 0,
                currentEer = null,
                currentAuc = null,
                calibratedThreshold = null,
                statusMessage = "Iniciando conexión..."
            ) 
        }
        
        val context = getApplication<Application>()
        val intent = Intent(context, FederatedLearningService::class.java).apply {
            action = FederatedLearningService.ACTION_START
            putExtra(FederatedLearningService.EXTRA_HOST, _uiState.value.serverHost)
            putExtra(FederatedLearningService.EXTRA_PORT, 8080)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }

    private companion object {
        const val HISTORY_LIMIT = 20
    }
}
