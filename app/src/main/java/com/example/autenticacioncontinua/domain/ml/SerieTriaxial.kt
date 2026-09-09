package com.example.autenticacioncontinua.domain.ml

/**
 * Una serie de lecturas triaxiales guardada en arrays primitivos.
 *
 * POR QUE EXISTE. Hasta el 06/09 el ventaneo pedia al repositorio la lista
 * entera de lecturas de las dos ultimas semanas y despues la convertia a
 * arrays. Con 1 389 630 filas de acelerometro y 1 348 976 de giroscopio esa
 * lista dejo de caber: la sesion federada murio con `OutOfMemoryError` dentro
 * de `prepareDataset` sin haber llegado a abrir el canal gRPC, y el sintoma
 * visible fue "el servidor no ve al cliente", que apunta justo al sitio
 * equivocado.
 *
 * LO CARO NO ERAN LOS DATOS, ERA EL EMPAQUETADO. Los mismos 1,4 millones de
 * muestras ocupan 24 bytes por muestra aqui —un `long` y tres `float`—, unos
 * 33 MB por sensor. Como objetos eran tres copias vivas a la vez: la lista de
 * entidades de Room, la lista de objetos de dominio que producia `map`, y la
 * lista filtrada que producia `filterNot`; cada objeto con su cabecera, su
 * referencia y su `dateString` propia. Ahi es donde se iban los 268 MB de
 * monton.
 *
 * NO ES UNA COLECCION DE PROPOSITO GENERAL y no debe convertirse en una. Solo
 * sabe crecer por el final, que es como se lee una serie temporal, y entregar
 * sus cuatro arrays al interpolador.
 *
 * NO ES SEGURA ENTRE HILOS. Se llena en un unico `withContext(Dispatchers.IO)`
 * y se lee despues.
 */
class SerieTriaxial(capacidadInicial: Int = CAPACIDAD_MINIMA) {

    private var t = LongArray(capacidadInicial.coerceAtLeast(CAPACIDAD_MINIMA))
    private var ejeX = FloatArray(t.size)
    private var ejeY = FloatArray(t.size)
    private var ejeZ = FloatArray(t.size)

    /** Muestras almacenadas. */
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /**
     * Instante de la ultima muestra anadida, o `Long.MIN_VALUE` si no hay
     * ninguna. Lo usa el bucle de lectura por bloques para pedir el siguiente.
     */
    val ultimoInstante: Long get() = if (size == 0) Long.MIN_VALUE else t[size - 1]

    /**
     * Anade una muestra al final.
     *
     * NO COMPRUEBA EL ORDEN, y es deliberado: quien llena la serie lee de una
     * consulta que ya viene ordenada por (timestamp, id), y verificar en cada
     * una de 1,4 millones de muestras algo que garantiza el `ORDER BY` seria
     * pagar por desconfiar de SQLite. La comprobacion que si tiene sentido
     * —que la serie completa sea monotona— la hace [esMonotona] una sola vez.
     */
    fun anadir(tMs: Long, x: Float, y: Float, z: Float) {
        if (size == t.size) crecer()
        t[size] = tMs
        ejeX[size] = x
        ejeY[size] = y
        ejeZ[size] = z
        size++
    }

    /**
     * Si los instantes no decrecen nunca.
     *
     * El interpolador y el detector de sesiones dan por hecho que la serie va
     * hacia adelante en el tiempo; si no lo hiciera, produciria ventanas con
     * senal inventada en lugar de fallar. Una muestra con el instante anterior
     * al de la previa es posible en la practica: basta un ajuste de reloj del
     * sistema entre dos rafagas.
     */
    fun esMonotona(): Boolean {
        for (i in 1 until size) if (t[i] < t[i - 1]) return false
        return true
    }

    /**
     * Los instantes, exactamente [size] elementos.
     *
     * Copia si sobra capacidad y devuelve el array interno si encaja justo: el
     * interpolador solo lee, y una copia de 11 MB por sensor cuando no hace
     * falta es precisamente lo que este tipo existe para evitar.
     */
    fun tiempos(): LongArray = if (size == t.size) t else t.copyOf(size)

    fun x(): FloatArray = if (size == ejeX.size) ejeX else ejeX.copyOf(size)

    fun y(): FloatArray = if (size == ejeY.size) ejeY else ejeY.copyOf(size)

    fun z(): FloatArray = if (size == ejeZ.size) ejeZ else ejeZ.copyOf(size)

    private fun crecer() {
        // Factor 1,5 y no 2: con series de millones de muestras, duplicar
        // desperdicia hasta un array entero de mas justo cuando el monton esta
        // mas lleno. La capacidad inicial viene del COUNT, asi que en el camino
        // normal esto no llega a ejecutarse ninguna vez.
        val nueva = (t.size + (t.size shr 1)).coerceAtLeast(t.size + CAPACIDAD_MINIMA)
        t = t.copyOf(nueva)
        ejeX = ejeX.copyOf(nueva)
        ejeY = ejeY.copyOf(nueva)
        ejeZ = ejeZ.copyOf(nueva)
    }

    companion object {
        const val CAPACIDAD_MINIMA = 1024
    }
}
