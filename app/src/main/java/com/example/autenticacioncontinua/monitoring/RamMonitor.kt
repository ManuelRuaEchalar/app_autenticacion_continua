package com.example.autenticacioncontinua.monitoring

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface IRamMonitor {
    fun getUsedRamMb(): Float
    fun startMeasurement(tag: String)
    fun stopMeasurement(tag: String): RamMeasurement
}

data class RamMeasurement(
    val tag: String,
    val startUsedMb: Float,
    val peakUsedMb: Float,
    val endUsedMb: Float,
    val timestampMs: Long
)

class RamMonitorImpl(private val context: Context) : IRamMonitor {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val scope = CoroutineScope(Dispatchers.Default)

    private val measurements = mutableMapOf<String, MeasurementState>()

    private class MeasurementState(
        val startUsedMb: Float,
        var peakUsedMb: Float,
        var job: Job? = null
    )

    override fun getUsedRamMb(): Float {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem - memInfo.availMem) / (1024f * 1024f)
    }

    override fun startMeasurement(tag: String) {
        val startMem = getUsedRamMb()
        val state = MeasurementState(startUsedMb = startMem, peakUsedMb = startMem)
        
        state.job = scope.launch {
            while (isActive) {
                delay(500)
                val current = getUsedRamMb()
                if (current > state.peakUsedMb) {
                    state.peakUsedMb = current
                }
            }
        }
        
        measurements[tag] = state
    }

    override fun stopMeasurement(tag: String): RamMeasurement {
        val state = measurements.remove(tag)
        state?.job?.cancel()

        val endUsedMb = getUsedRamMb()
        val startUsedMb = state?.startUsedMb ?: endUsedMb
        val peakUsedMb = maxOf(state?.peakUsedMb ?: endUsedMb, endUsedMb)

        return RamMeasurement(
            tag = tag,
            startUsedMb = startUsedMb,
            peakUsedMb = peakUsedMb,
            endUsedMb = endUsedMb,
            timestampMs = System.currentTimeMillis()
        )
    }
}
