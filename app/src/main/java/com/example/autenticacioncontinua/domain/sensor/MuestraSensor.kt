package com.example.autenticacioncontinua.domain.sensor

import kotlinx.coroutines.flow.Flow

/** Los tres sensores inerciales que usa el estudio. */
enum class TipoSensor(
    /** Constante de `android.hardware.Sensor`. Se guarda aquí para que el
     *  dominio no dependa del SDK: la capa de dispositivo la traduce. */
    val codigoAndroid: Int,
    val clave: String
) {
    ACELEROMETRO(1, "acc"),      // Sensor.TYPE_ACCELEROMETER
    GIROSCOPIO(4, "gyr"),        // Sensor.TYPE_GYROSCOPE
    MAGNETOMETRO(2, "mag")       // Sensor.TYPE_MAGNETIC_FIELD
}

/**
 * Una lectura de tres ejes, con SUS DOS RELOJES.
 *
 * POR QUÉ DOS Y NO UNO. Son para cosas distintas y ninguno sirve para las dos:
 *
 * - [tMonotonoNs] es el único válido para calcular INTERVALOS entre muestras, y
 *   por tanto la tasa efectiva de muestreo y los huecos. Viene de
 *   `SensorEvent.timestamp`, que no salta.
 * - [tParedMs] es el único que sirve para CASAR con el resto del mundo: el
 *   cuaderno de campo, las demás tablas, la hora a la que vino el participante.
 *
 * El reloj de pared puede saltar hacia atrás por NTP en mitad de un bloque de
 * cinco minutos. Si se usara para medir intervalos, ese salto fabricaría un
 * hueco o un solapamiento que no ocurrió, y el análisis lo leería como que el
 * participante dejó de teclear. Por eso [tParedMs] NO se lee del sistema en
 * cada muestra: se ancla una vez al empezar y se deriva del monótono. Ver
 * [RelojDeSensor].
 */
data class MuestraSensor(
    val tipo: TipoSensor,
    val x: Float,
    val y: Float,
    val z: Float,
    /** Derivado del monótono desde un ancla. No salta. */
    val tParedMs: Long,
    /** Monótono desde el arranque, en ns. El bueno para intervalos. */
    val tMonotonoNs: Long,
    /** `SensorManager.SENSOR_STATUS_*`. Interesa sobre todo en el magnetómetro. */
    val precision: Int
)

/**
 * Una fuente de muestras de un sensor.
 *
 * POR QUÉ ESTA INTERFAZ. Hasta el 24/08 había dos clases —acelerómetro y
 * giroscopio— idénticas salvo el tipo de sensor y el modelo de datos: mismo
 * registro, mismo flujo, misma parada. Añadir el magnetómetro por copia habría
 * dejado tres copias del mismo código y tres sitios donde arreglar el mismo
 * fallo. Las interfaces viejas se conservan como adaptadores sobre ésta para
 * no tocar a la recogida ambiental.
 */
interface IFuenteSensor {

    val tipo: TipoSensor

    /**
     * Si el terminal tiene este sensor.
     *
     * Hay móviles sin magnetómetro, y la aplicación tiene que seguir
     * funcionando y DEJAR CONSTANCIA en vez de fallar: una sesión sin campo
     * magnético sigue siendo una sesión válida con una columna a nulo.
     */
    val disponible: Boolean

    /** Frecuencia solicitada, en Hz. Android la trata como una sugerencia. */
    val hzSolicitados: Int

    fun iniciar()
    fun detener()
    fun flujo(): Flow<MuestraSensor>
}
