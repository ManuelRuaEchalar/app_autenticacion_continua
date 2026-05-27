package com.example.autenticacioncontinua.device.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Reads TYPE_GYROSCOPE at 50 Hz (20 000 µs period).
 * Matches UCI-HAR / HMOG reference sampling rate and
 * synchronises trivially with the accelerometer.
 */
class GyroscopeSensorImpl(
    context: Context
) : IGyroscopeSensor, SensorEventListener {

    companion object {
        private const val SAMPLING_PERIOD_US = 20_000 // 50 Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _sensorDataFlow = MutableSharedFlow<GyroscopeData>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun startListening() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US)
        }
    }

    override fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun getSensorDataFlow(): Flow<GyroscopeData> = _sensorDataFlow.asSharedFlow()

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val data = GyroscopeData(
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
