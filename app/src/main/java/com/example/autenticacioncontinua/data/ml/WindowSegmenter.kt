package com.example.autenticacioncontinua.data.ml

import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class WindowSegmenter(
    private val accelerometerRepository: IAccelerometerRepository,
    private val gyroscopeRepository: IGyroscopeRepository
) : IWindowSegmenter {

    override suspend fun getWindows(minWindows: Int): List<FloatArray>? = withContext(Dispatchers.IO) {
        val accelData = accelerometerRepository.getAllAccelerometerData()
        val gyroData = gyroscopeRepository.getAllGyroscopeData()

        if (accelData.isEmpty() || gyroData.isEmpty()) return@withContext null

        val pairedData = mutableListOf<FloatArray>()
        
        var aIdx = 0
        var gIdx = 0
        
        // Two pointers to pair data by closest timestamp (tolerance +/- 10ms)
        while (aIdx < accelData.size && gIdx < gyroData.size) {
            val a = accelData[aIdx]
            val g = gyroData[gIdx]
            
            val diff = a.timestamp - g.timestamp
            
            if (abs(diff) <= 10) {
                // Matched! Order: [gx, gy, gz, ax, ay, az]
                pairedData.add(floatArrayOf(g.x, g.y, g.z, a.x, a.y, a.z))
                aIdx++
                gIdx++
            } else if (diff > 10) {
                // accel is ahead of gyro, advance gyro
                gIdx++
            } else {
                // gyro is ahead of accel, advance accel
                aIdx++
            }
        }
        
        val windowSize = 128
        val stride = 64
        val windows = mutableListOf<FloatArray>()
        
        var start = 0
        while (start + windowSize <= pairedData.size) {
            // Flatten the window
            val window = FloatArray(windowSize * 6)
            var k = 0
            for (i in start until start + windowSize) {
                val row = pairedData[i]
                for (j in 0 until 6) {
                    window[k++] = row[j]
                }
            }
            windows.add(window)
            start += stride
        }
        
        if (windows.size >= minWindows) {
            windows
        } else {
            null
        }
    }
}
