package com.example.autenticacioncontinua.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.autenticacioncontinua.federated.FederatedLearningService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.example.autenticacioncontinua.BuildConfig

data class FederatedUiState(
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val currentEer: Double? = null,
    val serverHost: String = BuildConfig.SERVER_HOST,
    val statusMessage: String = "Inactivo"
)

class FederatedViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FederatedUiState())
    val uiState: StateFlow<FederatedUiState> = _uiState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FederatedLearningService.ACTION_FL_PROGRESS -> {
                    val round = intent.getIntExtra(FederatedLearningService.EXTRA_ROUND, 0)
                    val eer = intent.getDoubleExtra(FederatedLearningService.EXTRA_EER, -1.0)
                    _uiState.update { 
                        it.copy(
                            currentRound = round,
                            currentEer = eer,
                            statusMessage = "Ronda $round completada. EER: ${String.format("%.4f", eer)}"
                        ) 
                    }
                }
                FederatedLearningService.ACTION_FL_DONE -> {
                    _uiState.update { 
                        it.copy(
                            isRunning = false,
                            statusMessage = "Entrenamiento federado finalizado exitosamente."
                        ) 
                    }
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
}
