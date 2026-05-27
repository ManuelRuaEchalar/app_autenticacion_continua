package com.example.autenticacioncontinua.device.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Reads TYPE_ACCELEROMETER at 50 Hz (20 000 µs period).
 * Matches UCI-HAR / HMOG reference sampling rate.
 */
class AccelerometerSensorImpl(
    context: Context
) : IAccelerometerSensor, SensorEventListener {

    companion object {
        private const val SAMPLING_PERIOD_US = 20_000 // 50 Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _sensorDataFlow = MutableSharedFlow<AccelerometerData>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun startListening() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US)
        }
    }

    override fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun getSensorDataFlow(): Flow<AccelerometerData> = _sensorDataFlow.asSharedFlow()

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val data = AccelerometerData(
                    x = it.values[0],
                    y = it.values[1],
                    z = it.values[2],
                    timestamp = System.currentTimeMillis()
                )
                _sensorDataFlow.tryEmit(data)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
