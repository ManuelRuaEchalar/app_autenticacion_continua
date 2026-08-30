package com.example.autenticacioncontinua.monitoring

import android.os.SystemClock
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

/**
 * Memoria del PROCESO, no del dispositivo.
 *
 * QUÉ SE CORRIGIÓ (23/08). La versión anterior devolvía
 * `ActivityManager.MemoryInfo.totalMem - availMem`, es decir la memoria en uso
 * de TODO el dispositivo: el sistema, el lanzador y cualquier otra aplicación
 * abierta. Sobre las bases de campo del 17/08 daba entre 2 868 y 4 435 MB, que
 * no es el consumo de esta app —cuyo orden real son un par de cientos de MB— y
 * que además fluctúa por causas ajenas al experimento. Cualquier comparación
 * entre configuraciones de sensores hecha con ese número medía el ruido del
 * resto del teléfono.
 *
 * Ahora delega en [FuenteMemoria], que lee el PSS del proceso con
 * `Debug.MemoryInfo`, que es lo que pide el perfil aprobado.
 *
 * SIGUE MIDIENDO POR OPERACIÓN porque `FlowerGrpcClient` lo usa así y la
 * memoria SÍ se puede medir en ventanas cortas —a diferencia de la batería—.
 * Para bloques sostenidos existe [MonitorBloque], que además da mínimo y media
 * y no sólo el pico.
 */
class RamMonitorImpl(
    private val fuente: FuenteMemoria = FuenteMemoriaAndroid(),
    private val periodoMuestreoMs: Long = 500L,
    private val alcance: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : IRamMonitor {

    private class Estado(val inicioMb: Float, var picoMb: Float, var job: Job? = null)

    private val mediciones = mutableMapOf<String, Estado>()

    override fun getUsedRamMb(): Float = fuente.pssProcesoKb() / 1024f

    override fun startMeasurement(tag: String) {
        mediciones.remove(tag)?.job?.cancel()
        val inicio = getUsedRamMb()
        val estado = Estado(inicioMb = inicio, picoMb = inicio)
        estado.job = alcance.launch {
            while (isActive) {
                delay(periodoMuestreoMs)
                val actual = getUsedRamMb()
                if (actual > estado.picoMb) estado.picoMb = actual
            }
        }
        mediciones[tag] = estado
    }

    override fun stopMeasurement(tag: String): RamMeasurement {
        val estado = mediciones.remove(tag)
        estado?.job?.cancel()
        val finMb = getUsedRamMb()
        return RamMeasurement(
            tag = tag,
            startUsedMb = estado?.inicioMb ?: finMb,
            peakUsedMb = maxOf(estado?.picoMb ?: finMb, finMb),
            endUsedMb = finMb,
            timestampMs = SystemClock.elapsedRealtime()
        )
    }
}
