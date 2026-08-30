package com.example.autenticacioncontinua.device.sensor

import android.content.Context
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Acelerómetro para la RECOGIDA AMBIENTAL, a 50 Hz.
 *
 * AHORA ES UN ADAPTADOR, no una implementación. Toda la mecánica —registro,
 * flujo, parada, alineación de relojes— vive en [FuenteSensorAndroid]; aquí
 * sólo queda traducir [com.example.autenticacioncontinua.domain.sensor.MuestraSensor]
 * al `AccelerometerData` que ya consumen `SessionManagerImpl` y el repositorio.
 *
 * POR QUÉ SE CONSERVA LA INTERFAZ VIEJA en vez de migrar a sus consumidores.
 * Porque la recogida por ráfagas lleva funcionando desde el 12/08 y tiene
 * dentro 1,3 millones de filas del trabajo de campo; la restricción que fijó el
 * usuario el 23/08 es no tocar su lógica. Un adaptador de diez líneas cumple
 * las dos cosas: quita la duplicación y no cambia nada de lo que ya funciona.
 *
 * SIGUE A 50 Hz, no a los 100 Hz del estudio controlado. La tasa de la recogida
 * ambiental es parte de los datos ya recogidos: subirla ahora partiría el
 * corpus en dos regímenes de muestreo distintos y haría incomparables las
 * ráfagas de agosto con las de octubre.
 *
 * `timestamp` SIGUE SIENDO EL DE PARED, como antes. Es el que ya está en las
 * 1,3 millones de filas de `accelerometer_data` y el que espera
 * `WindowSegmenter` para cortar sesiones por hueco de 30 s. El reloj monótono
 * está disponible en la fuente genérica y lo usa el corpus controlado, que sí
 * lo necesita.
 */
class AccelerometerSensorImpl(
    context: Context,
    private val fuente: FuenteSensorAndroid = FuenteSensorAndroid(
        context = context,
        tipo = TipoSensor.ACELEROMETRO,
        hzSolicitados = HZ_AMBIENTAL
    )
) : IAccelerometerSensor {

    override fun startListening() = fuente.iniciar()

    override fun stopListening() = fuente.detener()

    override fun getSensorDataFlow(): Flow<AccelerometerData> = fuente.flujo().map {
        AccelerometerData(x = it.x, y = it.y, z = it.z, timestamp = it.tParedMs)
    }

    companion object {
        /** 50 Hz: la tasa de UCI-HAR y HMOG, y la de todo lo ya recogido. */
        const val HZ_AMBIENTAL = 50
    }
}
