package com.example.autenticacioncontinua.data.sensor

import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.domain.sensor.MuestraSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor

/**
 * Junta las tres corrientes de sensor en una sola fila por instante.
 *
 * EL PROBLEMA. Los tres sensores entregan por su cuenta, con sus propias
 * cadencias y sin coordinarse: no hay ningún instante en que lleguen los tres a
 * la vez. Alguien tiene que decidir qué es "la muestra del instante t".
 *
 * LA DECISIÓN: EL ACELERÓMETRO MANDA. Cada muestra suya genera una fila, y el
 * giroscopio y el magnetómetro aportan su ÚLTIMA lectura recibida. Tres
 * razones:
 *
 * 1. El acelerómetro está en todos los terminales; el magnetómetro no.
 * 2. Es el sensor de referencia en la literatura de autenticación continua, y
 *    el que ya rige las 1,3 millones de filas de la recogida ambiental.
 * 3. Un maestro fijo produce una cadencia regular; alternar el maestro según
 *    quién llegue antes produciría una serie con jitter propio.
 *
 * POR QUÉ RETENER Y NO INTERPOLAR. Interpolar entre dos lecturas de giroscopio
 * es un filtro de paso bajo disfrazado: suaviza justo el contenido de alta
 * frecuencia —el temblor, el micro-movimiento al pulsar— que es donde puede
 * estar la señal de identidad. Retener el último valor no inventa nada; a 100
 * Hz el desfase máximo es un periodo, 10 ms, y queda acotado por
 * [desfaseMaximoNs].
 *
 * NO SE EMITE NADA HASTA TENER GIROSCOPIO. Una fila con el giroscopio a cero
 * porque aún no había llegado su primera lectura sería un dato falso —cero es
 * una velocidad angular perfectamente posible— y caería justo al principio de
 * cada bloque, que es donde el participante empieza a teclear. Se descartan esas
 * primeras muestras y se cuentan en [descartadasSinGiroscopio].
 *
 * EL MAGNETÓMETRO SÍ PUEDE FALTAR: va a nulo, que es distinto de cero y el
 * análisis puede filtrarlo. Hay terminales sin él.
 *
 * CLASE PURA: sin Android, sin corrutinas, sin relojes propios. Se prueba
 * entera en la JVM.
 */
class AlineadorInercial(
    private val bloqueId: Long,
    private val desfaseMaximoNs: Long = DESFASE_MAXIMO_POR_DEFECTO_NS
) {

    private var gyr: MuestraSensor? = null
    private var mag: MuestraSensor? = null

    var descartadasSinGiroscopio: Long = 0
        private set

    /** Muestras en las que el giroscopio venía más viejo que [desfaseMaximoNs]. */
    var conGiroscopioRancio: Long = 0
        private set

    var emitidas: Long = 0
        private set

    /**
     * @return una fila si [muestra] es del acelerómetro y hay giroscopio
     *   reciente; `null` si sólo actualiza el estado o si aún no se puede
     *   emitir.
     */
    fun aceptar(muestra: MuestraSensor): MuestraInercialEntity? {
        when (muestra.tipo) {
            TipoSensor.GIROSCOPIO -> { gyr = muestra; return null }
            TipoSensor.MAGNETOMETRO -> { mag = muestra; return null }
            TipoSensor.ACELEROMETRO -> Unit
        }

        val g = gyr
        if (g == null) {
            descartadasSinGiroscopio++
            return null
        }
        if (muestra.tMonotonoNs - g.tMonotonoNs > desfaseMaximoNs) conGiroscopioRancio++

        // El magnetómetro rancio se descarta a nulo en vez de retenerse: es la
        // variable que menos importa y arrastrar una lectura de hace un segundo
        // como si fuera de ahora sería peor que no tenerla.
        val m = mag?.takeIf { muestra.tMonotonoNs - it.tMonotonoNs <= desfaseMaximoNs }

        emitidas++
        return MuestraInercialEntity(
            bloqueId = bloqueId,
            tParedMs = muestra.tParedMs,
            tMonotonoNs = muestra.tMonotonoNs,
            accX = muestra.x, accY = muestra.y, accZ = muestra.z,
            gyrX = g.x, gyrY = g.y, gyrZ = g.z,
            magX = m?.x, magY = m?.y, magZ = m?.z
        )
    }

    companion object {
        /**
         * 25 ms: dos periodos y medio a 100 Hz.
         *
         * Con un periodo justo, cualquier retraso normal de entrega marcaría la
         * muestra como rancia y el contador no diría nada. Con mucho más, se
         * dejaría pasar como simultáneo lo que no lo es.
         */
        const val DESFASE_MAXIMO_POR_DEFECTO_NS = 25_000_000L
    }
}
