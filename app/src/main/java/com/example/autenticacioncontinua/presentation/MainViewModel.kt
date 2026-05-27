package com.example.autenticacioncontinua.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.session.ISessionManager
import com.example.autenticacioncontinua.domain.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.net.Uri
import com.example.autenticacioncontinua.domain.export.IDataExportService

data class UiState(
    val sessionState: SessionState = SessionState.IDLE,
    val todayStat: DailySessionStat? = null,
    val recordedDates: List<String> = emptyList(),
    val selectedDate: String = "",
    val gyroscopeDataForDate: List<GyroscopeData> = emptyList(),
    val accelerometerDataForDate: List<AccelerometerData> = emptyList(),
    val totalGyroPoints: Int = 0,
    val totalAccelPoints: Int = 0,
    val databaseSizeBytes: Long = 0L,
    val isExporting: Boolean = false,
    val exportSuccessMessage: String? = null,
    val exportErrorMessage: String? = null
)

class MainViewModel(
    private val gyroRepository: IGyroscopeRepository,
    private val accelRepository: IAccelerometerRepository,
    private val sessionManager: ISessionManager,
    private val dataExportService: IDataExportService,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        startPollingState()
    }

    /**
     * Polls session state, today's stats and DB size every 2 seconds.
     */
    private fun startPollingState() {
        viewModelScope.launch {
            while (isActive) {
                val today = getCurrentDateString()
                val state = sessionManager.getState()
                val stat = gyroRepository.getDailySessionStat(today)
                val dbSize = getDatabaseSize()

                _uiState.value = _uiState.value.copy(
                    sessionState = state,
                    todayStat = stat,
                    databaseSizeBytes = dbSize
                )

                delay(2_000)
            }
        }
    }

    fun loadRecordedDates() {
        viewModelScope.launch {
            val dates = gyroRepository.getRecordedDates()
            _uiState.value = _uiState.value.copy(recordedDates = dates)
        }
    }

    fun selectDate(date: String) {
        viewModelScope.launch {
            val gyroData = gyroRepository.getGyroscopeDataByDate(date)
            val accelData = accelRepository.getAccelerometerDataByDate(date)
            _uiState.value = _uiState.value.copy(
                selectedDate = date,
                gyroscopeDataForDate = gyroData,
                accelerometerDataForDate = accelData,
                totalGyroPoints = gyroData.size,
                totalAccelPoints = accelData.size
            )
        }
    }

    fun exportDataToCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true, exportSuccessMessage = null, exportErrorMessage = null)
            val result = dataExportService.exportToCsv(uri)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isExporting = false, exportSuccessMessage = "Datos exportados correctamente.")
            } else {
                _uiState.value = _uiState.value.copy(isExporting = false, exportErrorMessage = "Error al exportar: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun clearExportMessages() {
        _uiState.value = _uiState.value.copy(exportSuccessMessage = null, exportErrorMessage = null)
    }

    /**
     * Calculates the total size of the Room database files (main + WAL + SHM).
     */
    private fun getDatabaseSize(): Long {
        val dbPath = appContext.getDatabasePath("continuous_auth_db")
        var totalSize = 0L
        if (dbPath.exists()) totalSize += dbPath.length()

        val walFile = File(dbPath.path + "-wal")
        if (walFile.exists()) totalSize += walFile.length()

        val shmFile = File(dbPath.path + "-shm")
        if (shmFile.exists()) totalSize += shmFile.length()

        return totalSize
    }

    private fun getCurrentDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}
