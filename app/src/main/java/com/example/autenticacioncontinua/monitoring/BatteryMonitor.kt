package com.example.autenticacioncontinua.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

interface IBatteryMonitor {
    fun getCurrentLevel(): Float
    fun startMeasurement(tag: String)
    fun stopMeasurement(tag: String): BatteryMeasurement
}

data class BatteryMeasurement(
    val tag: String,
    val startLevel: Float,
    val endLevel: Float,
    val deltaPercent: Float,
    val durationMs: Long,
    val timestampMs: Long
)

class BatteryMonitorImpl(private val context: Context) : IBatteryMonitor {

    private val startLevels = mutableMapOf<String, Float>()
    private val startTimes = mutableMapOf<String, Long>()

    override fun getCurrentLevel(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return -1f
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (scale > 0) level / scale.toFloat() * 100f else -1f
    }

    override fun startMeasurement(tag: String) {
        startLevels[tag] = getCurrentLevel()
        startTimes[tag] = System.currentTimeMillis()
    }

    override fun stopMeasurement(tag: String): BatteryMeasurement {
        val endLevel = getCurrentLevel()
        val endTime = System.currentTimeMillis()
        
        val startLevel = startLevels[tag] ?: endLevel
        val startTime = startTimes[tag] ?: endTime
        
        // Calculate delta (start - end), since battery drops. 
        // If it charged, delta will be negative.
        val delta = if (startLevel >= 0f && endLevel >= 0f) {
            startLevel - endLevel
        } else {
            0f
        }

        startLevels.remove(tag)
        startTimes.remove(tag)

        return BatteryMeasurement(
            tag = tag,
            startLevel = startLevel,
            endLevel = endLevel,
            deltaPercent = delta,
            durationMs = endTime - startTime,
            timestampMs = endTime
        )
    }
}
