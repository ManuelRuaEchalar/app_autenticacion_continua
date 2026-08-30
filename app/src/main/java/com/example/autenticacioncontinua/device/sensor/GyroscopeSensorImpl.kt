package com.example.autenticacioncontinua.device.sensor

import android.content.Context
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Giroscopio para la RECOGIDA AMBIENTAL, a 50 Hz.
 *
 * Adaptador sobre [FuenteSensorAndroid], igual que
 * [AccelerometerSensorImpl]; ahí está el porqué completo.
 */
class GyroscopeSensorImpl(
    context: Context,
    private val fuente: FuenteSensorAndroid = FuenteSensorAndroid(
        context = context,
        tipo = TipoSensor.GIROSCOPIO,
        hzSolicitados = AccelerometerSensorImpl.HZ_AMBIENTAL
    )
) : IGyroscopeSensor {

    override fun startListening() = fuente.iniciar()

    override fun stopListening() = fuente.detener()

    override fun getSensorDataFlow(): Flow<GyroscopeData> = fuente.flujo().map {
        GyroscopeData(x = it.x, y = it.y, z = it.z, timestamp = it.tParedMs)
    }
}
