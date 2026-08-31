package com.example.autenticacioncontinua.domain.juego

/**
 * Cronómetro de una fase. Puro: el reloj se inyecta.
 *
 * EL BLOQUE DURA UN TIEMPO FIJO, CORTE DONDE CORTE EL TEXTO. Es la decisión 2.2
 * del plan y no es una comodidad de implementación: si el bloque terminara al
 * acabar el párrafo, quien teclea rápido aportaría menos datos que quien teclea
 * despacio, y la cantidad de señal quedaría correlacionada con la habilidad — es
 * decir, con la persona. Fijar el tiempo iguala la aportación de todos.
 *
 * INTERRUMPIR NO FALSEA EL TIEMPO. Una llamada entrante o la pantalla apagándose
 * cierran el bloque antes de hora; lo que se hace es MARCARLO, no estirar la
 * duración hasta el valor nominal. Un bloque de 40 s marcado como interrumpido
 * es analizable —basta excluirlo o ponderarlo—; uno de 40 s presentado como de
 * 100 s corrompe en silencio cualquier tasa por unidad de tiempo, empezando por
 * las pulsaciones por minuto.
 *
 * EL RELOJ ES INYECTABLE, como en [com.example.autenticacioncontinua.monitoring.MonitorBloque].
 * Es lo que permite comprobar en la JVM, con el reloj virtual de `runTest`, que
 * un bloque de cien segundos termina exactamente cuando debe, sin esperar cien
 * segundos ni usar `Thread.sleep`.
 */
class RelojBloque(
    val duracionMs: Long,
    private val ahora: () -> Long
) {

    private var inicio: Long? = null
    private var finForzado: Long? = null

    /** Vacío si el bloque no se interrumpió. */
    var motivoInterrupcion: String = ""
        private set

    val interrumpido: Boolean get() = motivoInterrupcion.isNotEmpty()

    val arrancado: Boolean get() = inicio != null

    fun iniciar() {
        inicio = ahora()
        finForzado = null
        motivoInterrupcion = ""
    }

    /** Instante de arranque, o el actual si aún no se ha llamado a [iniciar]. */
    val inicioMs: Long get() = inicio ?: ahora()

    /**
     * Milisegundos consumidos. Se congela al interrumpir: si siguiera corriendo,
     * la duración registrada crecería mientras la pantalla del resumen está
     * abierta.
     */
    val transcurridoMs: Long
        get() {
            val i = inicio ?: return 0L
            return ((finForzado ?: ahora()) - i).coerceAtLeast(0L)
        }

    val restanteMs: Long get() = (duracionMs - transcurridoMs).coerceAtLeast(0L)

    /** Progreso en [0, 1], para la barra de la pantalla. */
    val fraccion: Float
        get() = if (duracionMs <= 0L) 1f
        else (transcurridoMs.toFloat() / duracionMs).coerceIn(0f, 1f)

    /** Se agotó el tiempo nominal. */
    val terminadoPorTiempo: Boolean get() = arrancado && transcurridoMs >= duracionMs

    /** Terminó, por tiempo o porque algo lo cortó. */
    val terminado: Boolean get() = terminadoPorTiempo || interrumpido

    /**
     * Corta el bloque ahora y anota por qué.
     *
     * El motivo es obligatorio: un bloque interrumpido sin explicación es
     * indistinguible de uno que salió corto por un fallo del programa, y en el
     * análisis los dos casos se tratan distinto.
     *
     * Interrumpir dos veces no cambia nada: la primera interrupción es la que
     * paró el tecleo, y la segunda —típicamente el `onStop` que llega detrás de
     * la llamada entrante— llegaría con el bloque ya cerrado.
     */
    fun interrumpir(motivo: String) {
        require(motivo.isNotBlank()) {
            "interrumpir sin motivo. El motivo es el dato: sin el, un bloque " +
                "corto no se distingue de un fallo del programa."
        }
        if (interrumpido) return
        motivoInterrupcion = motivo.trim()
        finForzado = ahora()
    }
}
