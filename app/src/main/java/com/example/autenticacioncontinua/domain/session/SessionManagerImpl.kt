package com.example.autenticacioncontinua.domain.session

import android.util.Log
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Core business logic for IMU data collection sessions.
 *
 * Coordinates both gyroscope (50 Hz) and accelerometer (50 Hz) sensors.
 * Uses screen state events that work reliably from a foreground service.
 *
 * Flow:
 * 1. User unlocks the device  → 2-minute countdown starts.
 * 2. Screen stays on for 2 min → both sensors start recording.
 * 3. Screen turns off           → countdown resets, recording pauses.
 * 4. Recording stops after 15 min of data collected for the day.
 */
class SessionManagerImpl(
    private val gyroscopeSensor: IGyroscopeSensor,
    private val accelerometerSensor: IAccelerometerSensor,
    private val gyroscopeRepository: IGyroscopeRepository,
    private val accelerometerRepository: IAccelerometerRepository
) : ISessionManager {

    companion object {
        private const val TAG = "SessionManager"
        private const val REQUIRED_CONTINUOUS_USAGE_MS = 2 * 60 * 1000L  // 2 minutes
        private const val DAILY_LIMIT_MINUTES = 15
        private const val BATCH_SIZE = 500
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var countdownJob: Job? = null
    private var gyroRecordingJob: Job? = null
    private var accelRecordingJob: Job? = null

    @Volatile private var currentState = SessionState.IDLE

    // ── Public API ───────────────────────────────────────────────────────

    override fun onDeviceUnlocked() {
        Log.d(TAG, "Device unlocked / screen on")
        if (currentState == SessionState.RECORDING ||
            currentState == SessionState.DAILY_LIMIT_REACHED) return

        currentState = SessionState.MONITORING_USAGE
        startCountdown()
    }

    override fun onScreenOff() {
        Log.d(TAG, "Screen off")
        countdownJob?.cancel()

        if (currentState == SessionState.RECORDING) {
            stopRecording()
        }

        if (currentState != SessionState.DAILY_LIMIT_REACHED) {
            currentState = SessionState.IDLE
        }
    }

    override fun startMonitoring() {
        Log.d(TAG, "Service started – monitoring screen state (gyro + accel @ 50 Hz)")
        currentState = SessionState.IDLE

        scope.launch {
            val today = getCurrentDateString()
            val stat = gyroscopeRepository.getDailySessionStat(today)
            if (stat != null && stat.totalMinutesRecorded >= DAILY_LIMIT_MINUTES) {
                currentState = SessionState.DAILY_LIMIT_REACHED
                Log.d(TAG, "Daily limit already reached for today")
            }
        }
    }

    override fun stopMonitoring() {
        countdownJob?.cancel()
        stopRecording()
        scope.cancel()
    }

    override fun getState(): SessionState = currentState

    // ── Private logic ────────────────────────────────────────────────────

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            Log.d(TAG, "Starting 2-minute countdown…")
            delay(REQUIRED_CONTINUOUS_USAGE_MS)

            if (isActive) {
                Log.d(TAG, "2 minutes reached – checking daily limit")
                checkAndStartRecording()
            }
        }
    }

    private suspend fun checkAndStartRecording() {
        val today = getCurrentDateString()
        val stat = gyroscopeRepository.getDailySessionStat(today) ?: DailySessionStat(today, 0)

        if (stat.totalMinutesRecorded >= DAILY_LIMIT_MINUTES) {
            currentState = SessionState.DAILY_LIMIT_REACHED
            Log.d(TAG, "Daily limit reached. Not recording.")
            return
        }

        val remainingMinutes = DAILY_LIMIT_MINUTES - stat.totalMinutesRecorded
        startRecording(stat, today, remainingMinutes)
    }

    private fun startRecording(stat: DailySessionStat, todayStr: String, remainingMinutes: Int) {
        if (gyroRecordingJob?.isActive == true) return

        currentState = SessionState.RECORDING
        Log.d(TAG, "▶ Recording IMU – $remainingMinutes min remaining today")

        // Start both sensors simultaneously
        gyroscopeSensor.startListening()
        accelerometerSensor.startListening()

        val startTime = System.currentTimeMillis()
        val maxRecordingMs = remainingMinutes * 60 * 1000L

        // ── Gyroscope collection coroutine ──
        gyroRecordingJob = scope.launch {
            var lastSavedMinute = 0
            val dataBuffer = mutableListOf<GyroscopeData>()

            gyroscopeSensor.getSensorDataFlow()
                .buffer()
                .collect { rawData ->
                    val data = rawData.copy(dateString = todayStr)
                    dataBuffer.add(data)

                    if (dataBuffer.size >= BATCH_SIZE) {
                        gyroscopeRepository.saveGyroscopeData(dataBuffer.toList())
                        dataBuffer.clear()
                    }

                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedMinutes = (elapsedMs / 60_000).toInt()

                    if (elapsedMinutes > lastSavedMinute) {
                        lastSavedMinute = elapsedMinutes
                        val updatedStat = DailySessionStat(
                            todayStr,
                            stat.totalMinutesRecorded + elapsedMinutes
                        )
                        gyroscopeRepository.updateDailySessionStat(updatedStat)
                        Log.d(TAG, "  Progress: ${updatedStat.totalMinutesRecorded}/$DAILY_LIMIT_MINUTES min")
                    }

                    if (elapsedMs >= maxRecordingMs) {
                        if (dataBuffer.isNotEmpty()) {
                            gyroscopeRepository.saveGyroscopeData(dataBuffer.toList())
                            dataBuffer.clear()
                        }
                        val finalStat = DailySessionStat(
                            todayStr,
                            stat.totalMinutesRecorded + remainingMinutes
                        )
                        gyroscopeRepository.updateDailySessionStat(finalStat)
                        Log.d(TAG, "✓ Daily recording complete!")
                        stopAllSensors()
                        currentState = SessionState.DAILY_LIMIT_REACHED
                        accelRecordingJob?.cancel()
                        return@collect
                    }
                }
        }

        // ── Accelerometer collection coroutine ──
        accelRecordingJob = scope.launch {
            val dataBuffer = mutableListOf<AccelerometerData>()

            accelerometerSensor.getSensorDataFlow()
                .buffer()
                .collect { rawData ->
                    val data = rawData.copy(dateString = todayStr)
                    dataBuffer.add(data)

                    if (dataBuffer.size >= BATCH_SIZE) {
                        accelerometerRepository.saveAccelerometerData(dataBuffer.toList())
                        dataBuffer.clear()
                    }

                    // Time limit is controlled by the gyro coroutine (single source of truth)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    if (elapsedMs >= maxRecordingMs) {
                        if (dataBuffer.isNotEmpty()) {
                            accelerometerRepository.saveAccelerometerData(dataBuffer.toList())
                            dataBuffer.clear()
                        }
                        return@collect
                    }
                }
        }
    }

    private fun stopAllSensors() {
        gyroscopeSensor.stopListening()
        accelerometerSensor.stopListening()
    }

    private fun stopRecording() {
        gyroRecordingJob?.cancel()
        accelRecordingJob?.cancel()
        stopAllSensors()
        Log.d(TAG, "⏸ Recording stopped")
    }

    private fun getCurrentDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}
