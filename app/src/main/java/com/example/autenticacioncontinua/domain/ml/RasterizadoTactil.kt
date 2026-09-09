package com.example.autenticacioncontinua.domain.ml

/**
 * Convierte pulsaciones —que son EVENTOS— en señales continuas sobre la misma
 * rejilla temporal que los sensores inerciales.
 *
 * POR QUÉ HACE FALTA. La configuración C mete el canal táctil en el mismo
 * tensor `(128, n_canales)` que el acelerómetro y el giroscopio, y ese tensor
 * es una rejilla regular a 50 Hz. El tecleo no llega así: llega como una lista
 * de pulsaciones con instante de bajada y de subida. Hay que llevarlo a la
 * rejilla, y CÓMO se lleve decide qué aprende el modelo.
 *
 * TIENE QUE COINCIDIR CON `colab_preentreno_configuraciones.ipynb`. El encoder
 * de C se pre-entrena en el cuadernillo con esta misma transformación aplicada
 * a HMOG. Si aquí se hiciera distinto —interpolando entre toques, o midiendo el
 * contacto de otra forma— el modelo recibiría en el teléfono una señal con otra
 * estadística que la que vio al pre-entrenarse, y no fallaría nada: produciría
 * puntuaciones peores sin ninguna señal de error. Cualquier cambio aquí hay que
 * hacerlo en los dos sitios a la vez.
 *
 * LOS TRES CANALES, Y POR QUÉ ESTOS TRES:
 *
 *   [CONTACTO]  1.0 mientras hay un dedo apoyado, 0.0 el resto. Es lo que
 *               distingue el régimen de C: dice CUÁNDO hay interacción. El
 *               ritmo de tecleo —permanencia y vuelo— queda implícito en la
 *               forma de esta onda cuadrada, que es como el encoder
 *               convolucional puede verlo.
 *
 *   [X], [Y]    posición del último contacto conocido, MANTENIDA entre
 *               pulsaciones. No se interpola hacia la siguiente: entre dos
 *               teclas el dedo no está viajando por la pantalla en línea recta,
 *               está fuera de ella, y una rampa lineal inventaría un trazo que
 *               no ocurrió. Antes de la primera pulsación se usa el valor de la
 *               primera, para no meter un salto desde cero.
 *
 * NO HAY CANAL DE PRESIÓN, y es una decisión medida, no un olvido. En el corpus
 * de pre-entrenamiento la presión vale 1.0 en las 146 411 pulsaciones de las
 * sesiones de escritura: es una constante del aparato, no una medida. Es el
 * mismo motivo por el que `EventoTecleoEntity` deja `presion` nulable. Si en
 * los terminales del estudio resultara ser variable —hay que comprobarlo en la
 * fase 0— añadirla obligaría a re-pre-entrenar el encoder de C, no solo a tocar
 * esto.
 */
object RasterizadoTactil {

    /** Canales que produce, en orden. Van detrás de los inerciales. */
    const val CANALES = 3

    const val CONTACTO = 0
    const val X = 1
    const val Y = 2

    /**
     * Una pulsación ya resuelta a la línea de tiempo de la rejilla.
     *
     * @param tDown instante de bajada del dedo, en la misma unidad y origen que
     *   [rejilla].
     * @param tUp instante de subida. Si no es mayor que [tDown] la pulsación se
     *   ignora: es una tecla que se quedó abierta al terminar el bloque
     *   (`EventoTecleoEntity` guarda 0 en ese caso) y no se sabe cuánto duró.
     */
    data class Pulsacion(
        val tDown: Long,
        val tUp: Long,
        val x: Float?,
        val y: Float?
    )

    /**
     * Rasteriza [pulsaciones] sobre [rejilla].
     *
     * @param rejilla instantes de la rejilla regular, crecientes.
     * @return array `[canal][i]` con [CANALES] filas y `rejilla.size` columnas.
     */
    fun rasterizar(
        rejilla: LongArray,
        pulsaciones: List<Pulsacion>
    ): Array<FloatArray> {
        val n = rejilla.size
        val salida = Array(CANALES) { FloatArray(n) }
        if (n == 0) return salida

        val utiles = pulsaciones
            .filter { it.tUp > it.tDown }
            .sortedBy { it.tDown }

        // Sin pulsaciones el bloque no tiene canal táctil que ofrecer. Se
        // devuelven ceros y NO se inventa una posición: un bloque sin tecleo
        // existe —el participante pudo quedarse parado— y el modelo tiene que
        // poder distinguirlo de uno con actividad.
        if (utiles.isEmpty()) return salida

        // Posición: se arranca con la de la primera pulsación para no meter un
        // escalón desde cero al principio del bloque.
        var x = utiles.first().x ?: 0f
        var y = utiles.first().y ?: 0f

        var siguiente = 0
        for (i in 0 until n) {
            val t = rejilla[i]
            // Avanzar el cursor de pulsaciones que ya empezaron.
            while (siguiente < utiles.size && utiles[siguiente].tDown <= t) {
                utiles[siguiente].x?.let { x = it }
                utiles[siguiente].y?.let { y = it }
                siguiente++
            }
            salida[X][i] = x
            salida[Y][i] = y
        }

        // Contacto: se marca cada tramo por separado en vez de dentro del bucle
        // anterior, porque las pulsaciones pueden solaparse —dos dedos, o un
        // repetido— y marcar por tramos hace la unión sin contarlos dos veces.
        for (p in utiles) {
            var i = indiceDesde(rejilla, p.tDown)
            while (i < n && rejilla[i] <= p.tUp) {
                salida[CONTACTO][i] = 1f
                i++
            }
        }
        return salida
    }

    /** Primer índice de [rejilla] con valor >= [t]; `rejilla.size` si no hay. */
    private fun indiceDesde(rejilla: LongArray, t: Long): Int {
        var lo = 0
        var hi = rejilla.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rejilla[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
