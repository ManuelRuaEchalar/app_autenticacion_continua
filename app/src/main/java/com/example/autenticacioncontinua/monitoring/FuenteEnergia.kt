package com.example.autenticacioncontinua.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Lectura cruda del estado energético del dispositivo.
 *
 * POR QUÉ EXISTE ESTA INTERFAZ APARTE DEL MONITOR. Leer el hardware y ejecutar
 * una medición son dos responsabilidades distintas: la primera no se puede
 * probar sin un teléfono y la segunda es aritmética pura. Separándolas, toda la
 * lógica de agregación queda cubierta por pruebas unitarias y sólo el envoltorio
 * de plataforma necesita un dispositivo real.
 *
 * POR QUÉ NO EL PORCENTAJE. El monitor anterior medía `EXTRA_LEVEL /
 * EXTRA_SCALE`, es decir porcentaje con granularidad del 1%. Sobre las bases de
 * campo del 17/08 eso dio **669 de 676 medidas exactamente 0.0**: una ronda
 * federada dura 1.5 s de mediana y un 1% de batería son unos 40 mAh, o sea
 * minutos u horas de uso. El instrumento no podía resolver la magnitud que
 * debía medir.
 *
 * Y POR QUÉ EL CONTADOR DE CARGA TAMPOCO BASTA, medido el 24/08 en el Redmi
 * 23129RA5FL: `BATTERY_PROPERTY_CHARGE_COUNTER` promete µAh, pero en ese
 * terminal se mueve en escalones de **49 370 µAh** —el 1% de su batería—, o sea
 * que el fabricante lo deriva del porcentaje. En 60 s de muestreo cambió una
 * sola vez. Con esa resolución el contador no sirve para bloques de minutos, y
 * suponer lo contrario habría repetido el error anterior con otro nombre.
 *
 * LO QUE SÍ RESUELVE es `BATTERY_PROPERTY_CURRENT_NOW`: ~118 mA de media bajo
 * carga computacional en ese mismo terminal, con lecturas cada 200 ms.
 * Integrada en el tiempo da el consumo del bloque. Ver [MetodoConsumo], que
 * viaja con cada medición porque dos bloques medidos con instrumentos
 * distintos no son comparables.
 *
 * LA RESOLUCIÓN HAY QUE MEDIRLA EN CADA TERMINAL, no darla por supuesta: la
 * fija el fabricante. De eso se ocupa `CaracterizacionRecursosTest`, que se
 * ejecuta en los dos aparatos del estudio antes de recoger nada.
 */
interface FuenteEnergia {

    /**
     * Carga restante en µAh, o `null` si el terminal no expone el contador.
     *
     * Decrece al descargar. La diferencia entre dos lecturas es el consumo.
     */
    fun cargaMicroAh(): Long?

    /**
     * Corriente instantánea en µA, o `null` si no está disponible.
     *
     * OJO CON EL SIGNO: el convenio no está normalizado. La mayoría de
     * terminales devuelven negativo al descargar, pero algunos fabricantes lo
     * invierten. No se asume nada aquí; quien interprete el valor debe mirar el
     * signo observado en el terminal concreto y dejarlo documentado.
     */
    fun corrienteMicroA(): Long?

    /** Porcentaje 0-100, o `null`. Se conserva sólo como referencia gruesa. */
    fun porcentaje(): Float?

    /**
     * Si hay ALIMENTACIÓN EXTERNA CONECTADA, esté cargando activamente o no.
     *
     * Una medición de consumo hecha con el cable puesto no vale nada: la carga
     * sube en lugar de bajar y la corriente que ve el medidor es la que entrega
     * el cargador, no la que gasta el teléfono. El monitor la marca inválida en
     * vez de publicar un número sin sentido.
     *
     * SE PREGUNTA POR EL CABLE, NO POR `isCharging`. No son lo mismo: MIUI corta
     * la carga al 80% por protección de batería, y en ese estado `isCharging`
     * devuelve `false` con el cable puesto. El 24/08 la caracterización entera
     * del Redmi ec56958 se ejecutó enchufada dando `cargando: false`, y sus
     * cifras de consumo eran las del cargador. De ahí que esto mire
     * `EXTRA_PLUGGED`, que dice si hay algo enchufado.
     */
    fun estaCargando(): Boolean
}

class FuenteEnergiaAndroid(private val context: Context) : FuenteEnergia {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override fun cargaMicroAh(): Long? =
        leerPropiedad(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }   // la carga restante nunca es cero ni negativa

    override fun corrienteMicroA(): Long? =
        leerPropiedad(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    override fun porcentaje(): Float? {
        val intent = estadoBateria() ?: return null
        val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (nivel >= 0 && escala > 0) nivel / escala.toFloat() * 100f else null
    }

    /**
     * Tres comprobaciones, y basta con que UNA diga que sí.
     *
     * `EXTRA_PLUGGED` es la que de verdad importa —dice si hay un cable, un
     * cargador inalámbrico o un puerto USB conectado, cargue o no—, pero se
     * conservan las otras dos por si algún terminal no rellena ese campo. Ante
     * la duda se declara "cargando": una medición descartada de más cuesta un
     * bloque; una medición del cargador colada como consumo de la app
     * contamina el resultado del estudio.
     */
    override fun estaCargando(): Boolean = try {
        val intent = estadoBateria()
        val enchufado = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val estado = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        enchufado != 0 ||
            estado == BatteryManager.BATTERY_STATUS_CHARGING ||
            estado == BatteryManager.BATTERY_STATUS_FULL ||
            batteryManager.isCharging
    } catch (e: Exception) {
        Log.w(TAG, "no se pudo determinar si hay alimentacion externa: ${e.message}")
        // Ante un fallo de lectura, se supone que SI hay cable: descartar de
        // mas es recuperable; publicar el consumo del cargador, no.
        true
    }

    private fun estadoBateria(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /**
     * `getLongProperty` no lanza cuando la propiedad no existe: devuelve
     * `Long.MIN_VALUE` en unos terminales y 0 en otros. Ambos se traducen a
     * `null` para que quien llame no confunda "no disponible" con "cero".
     */
    private fun leerPropiedad(propiedad: Int): Long? = try {
        batteryManager.getLongProperty(propiedad)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
    } catch (e: Exception) {
        Log.w(TAG, "propiedad $propiedad no disponible: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "FuenteEnergiaAndroid"
    }
}
