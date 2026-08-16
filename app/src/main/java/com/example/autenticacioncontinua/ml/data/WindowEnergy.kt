package com.example.autenticacioncontinua.ml.data

import kotlin.math.sqrt

/**
 * Energía de movimiento de una ventana: media de las desviaciones típicas
 * POR EJE del acelerómetro.
 *
 * Por eje y no sobre la magnitud |a|: una rotación pura —girar el móvil en la
 * mano— cambia la proyección de la gravedad en cada eje pero deja |a| en ~9.8,
 * así que con la magnitud ese movimiento sería invisible. Es el mismo
 * estadístico que usa `SessionManagerImpl.hayUsoReal()` para el gate de
 * arranque, a propósito: una sola definición de "aquí hay comportamiento".
 *
 * Las ventanas llegan YA normalizadas por el scaler —tanto las propias
 * (WindowSegmenter) como las del pool de fondo (BackgroundPool)—, así que los
 * valores son directamente comparables entre sí. Si algún día una de las dos
 * fuentes dejara de normalizarse, este número mezclaría unidades y todo lo que
 * cuelga de él (filtro y emparejado) quedaría mal en silencio.
 *
 * Disposición de `values`: intercalada por canal, `values[t * nFeatures + c]`.
 */
object WindowEnergy {

    /** Canales del acelerómetro en `channelOrder`: acc_x, acc_y, acc_z. */
    private const val ACC_CHANNELS = 3

    fun accStdMedio(values: FloatArray, nFeatures: Int): Float {
        require(nFeatures >= ACC_CHANNELS) {
            "Se esperan al menos $ACC_CHANNELS canales de acelerómetro"
        }
        val n = values.size / nFeatures
        if (n < 2) return 0f

        var suma = 0.0
        for (c in 0 until ACC_CHANNELS) {
            var media = 0.0
            for (t in 0 until n) media += values[t * nFeatures + c]
            media /= n
            var varianza = 0.0
            for (t in 0 until n) {
                val d = values[t * nFeatures + c] - media
                varianza += d * d
            }
            // Varianza poblacional (divide por n), igual que `numpy.std` por
            // defecto, que es con lo que se calibraron los percentiles.
            suma += sqrt(varianza / n)
        }
        return (suma / ACC_CHANNELS).toFloat()
    }

    /**
     * Percentil de una lista de energías. Interpolación lineal entre los dos
     * vecinos, como `numpy.percentile`, para no separarse del análisis con el
     * que se eligieron las constantes.
     */
    fun percentil(valores: FloatArray, p: Double): Float {
        if (valores.isEmpty()) return 0f
        val orden = valores.sortedArray()
        if (orden.size == 1) return orden[0]
        val pos = (p / 100.0) * (orden.size - 1)
        val i = pos.toInt()
        val frac = pos - i
        return if (i + 1 < orden.size) {
            (orden[i] + frac * (orden[i + 1] - orden[i])).toFloat()
        } else {
            orden[orden.size - 1]
        }
    }
}
