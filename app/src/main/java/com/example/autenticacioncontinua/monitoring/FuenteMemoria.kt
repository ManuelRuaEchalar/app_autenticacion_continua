package com.example.autenticacioncontinua.monitoring

import android.os.Debug

/**
 * Lectura cruda de la memoria que ocupa ESTE proceso.
 *
 * POR QUÉ NO VALE LO QUE HABÍA. El monitor anterior calculaba
 * `ActivityManager.MemoryInfo.totalMem - availMem`, que es la memoria en uso de
 * TODO EL DISPOSITIVO: el sistema operativo, el lanzador, WhatsApp y lo que
 * hubiera abierto. Sobre las bases de campo del 17/08 daba entre 2 868 y
 * 4 435 MB, cifras que evidentemente no son el consumo de esta aplicación
 * —cuyo orden real son un par de cientos de MB— y que además suben y bajan por
 * causas ajenas al experimento.
 *
 * El perfil aprobado pide "memoria RAM utilizada por la aplicación" medida con
 * `Debug.MemoryInfo`, que es exactamente lo que hace esta clase.
 *
 * POR QUÉ PSS Y NO OTRA MÉTRICA. El PSS (Proportional Set Size) reparte la
 * memoria compartida entre los procesos que la usan, así que sumar el PSS de
 * varias apps no cuenta dos veces las bibliotecas del sistema. Es la métrica que
 * Android recomienda para "cuánta memoria le atribuyo a esta app".
 */
interface FuenteMemoria {

    /** PSS total del proceso actual, en kilobytes. */
    fun pssProcesoKb(): Long
}

class FuenteMemoriaAndroid : FuenteMemoria {

    /**
     * Se reutiliza la misma instancia entre llamadas: `getMemoryInfo` la
     * rellena, no la crea. El muestreo ocurre cada pocos cientos de
     * milisegundos durante minutos, y este proyecto mide consumo — asignar un
     * objeto por muestra sería gastar justo lo que se está midiendo.
     */
    private val info = Debug.MemoryInfo()

    override fun pssProcesoKb(): Long {
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }
}
