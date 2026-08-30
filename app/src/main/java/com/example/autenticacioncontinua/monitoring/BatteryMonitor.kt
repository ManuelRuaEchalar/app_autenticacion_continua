package com.example.autenticacioncontinua.monitoring

import android.os.SystemClock

interface IBatteryMonitor {
    fun getCurrentLevel(): Float
    fun startMeasurement(tag: String)
    fun stopMeasurement(tag: String): BatteryMeasurement
}

/**
 * Resultado de una medición de batería.
 *
 * [deltaPercent] se conserva por compatibilidad con `FlowerGrpcClient` y con la
 * tabla `resource_measurements`, PERO no debe usarse para comparar
 * configuraciones: su granularidad es del 1%. Sobre las bases de campo del
 * 17/08 valió exactamente 0.0 en 669 de 676 mediciones. El campo con contenido
 * real es [deltaMicroAh].
 *
 * [resolucionSuficiente] responde a la única pregunta que importa antes de
 * creerse una medición: ¿se movió el contador durante este bloque? Si no, el
 * bloque fue demasiado corto para este terminal y el número no vale.
 */
data class BatteryMeasurement(
    val tag: String,
    val startLevel: Float,
    val endLevel: Float,
    val deltaPercent: Float,
    val deltaMicroAh: Long?,
    val corrienteMediaMicroA: Double?,
    val estuvoCargando: Boolean,
    val resolucionSuficiente: Boolean,
    val durationMs: Long,
    val timestampMs: Long
)

/**
 * Consumo de batería medido con el CONTADOR DE CARGA, no con el porcentaje.
 *
 * QUÉ SE CORRIGIÓ (23/08). La versión anterior calculaba la diferencia de
 * `EXTRA_LEVEL / EXTRA_SCALE`: porcentaje entero. Una ronda federada dura 1.5 s
 * de mediana y un 1% de batería son unos 40 mAh, es decir minutos u horas de
 * uso; el instrumento no podía resolver la magnitud que debía medir y devolvía
 * ceros. `BATTERY_PROPERTY_CHARGE_COUNTER` da µAh y tiene resolución de órdenes
 * de magnitud mejor.
 *
 * LO QUE ESTA CLASE NO PUEDE ARREGLAR. Ni con el contador se puede medir el
 * consumo de una operación de 1.5 s: la cadencia de actualización del contador
 * la fija el fabricante. Por eso el protocolo del estudio mide sobre bloques
 * sostenidos de minutos con [MonitorBloque], y por eso aquí se publica
 * [BatteryMeasurement.resolucionSuficiente] — para que una medición inservible
 * se reconozca como tal en vez de entrar en el análisis disfrazada de cero.
 */
class BatteryMonitorImpl(
    private val fuente: FuenteEnergia
) : IBatteryMonitor {

    private class Estado(
        val nivel: Float?,
        val cargaMicroAh: Long?,
        val tMs: Long,
        var cargandoEnAlgunMomento: Boolean,
        val corrientes: MutableList<Long>
    )

    private val mediciones = mutableMapOf<String, Estado>()

    override fun getCurrentLevel(): Float = fuente.porcentaje() ?: -1f

    override fun startMeasurement(tag: String) {
        mediciones[tag] = Estado(
            nivel = fuente.porcentaje(),
            cargaMicroAh = fuente.cargaMicroAh(),
            tMs = SystemClock.elapsedRealtime(),
            cargandoEnAlgunMomento = fuente.estaCargando(),
            corrientes = fuente.corrienteMicroA()?.let { mutableListOf(it) } ?: mutableListOf()
        )
    }

    override fun stopMeasurement(tag: String): BatteryMeasurement {
        val ahora = SystemClock.elapsedRealtime()
        val nivelFin = fuente.porcentaje()
        val cargaFin = fuente.cargaMicroAh()
        val cargando = fuente.estaCargando()
        val corrienteFin = fuente.corrienteMicroA()

        val estado = mediciones.remove(tag)
        estado?.corrientes?.let { if (corrienteFin != null) it += corrienteFin }

        val nivelIni = estado?.nivel ?: nivelFin
        val cargaIni = estado?.cargaMicroAh
        val tIni = estado?.tMs ?: ahora

        val delta = if (nivelIni != null && nivelFin != null) nivelIni - nivelFin else 0f
        val deltaUAh = if (cargaIni != null && cargaFin != null) cargaIni - cargaFin else null

        val corrientes = estado?.corrientes ?: emptyList<Long>()
        val corrienteMedia =
            if (corrientes.isEmpty()) null
            // Valor absoluto: el signo de CURRENT_NOW no está normalizado entre
            // fabricantes y aquí sólo interesa la magnitud.
            else corrientes.map { kotlin.math.abs(it).toDouble() }.average()

        return BatteryMeasurement(
            tag = tag,
            startLevel = nivelIni ?: -1f,
            endLevel = nivelFin ?: -1f,
            deltaPercent = delta,
            deltaMicroAh = deltaUAh,
            corrienteMediaMicroA = corrienteMedia,
            estuvoCargando = cargando || (estado?.cargandoEnAlgunMomento ?: false),
            // Sólo es creíble si el contador existe Y se movió.
            resolucionSuficiente = deltaUAh != null && deltaUAh != 0L,
            durationMs = ahora - tIni,
            timestampMs = System.currentTimeMillis()
        )
    }
}
