package com.example.autenticacioncontinua.monitoring

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Algo que puede ejecutarse repetidamente durante un bloque de medición.
 *
 * Una sola pasada: el protocolo la repite hasta agotar la duración del bloque.
 * Se define así, y no como "corre durante N ms", para que la actividad no tenga
 * que saber nada de relojes ni de duraciones.
 */
fun interface ActividadMedible {
    suspend fun unaPasada()
}

/**
 * Una celda del diseño: qué se ejecuta, bajo qué régimen, y cuánto.
 *
 * @param duracionMs debe superar la resolución del contador de carga del
 *   terminal. Cuál es ese mínimo NO se puede saber sin medirlo en cada aparato:
 *   es lo que averigua `CaracterizacionRecursosTest`.
 */
data class EspecificacionBloque(
    val nombre: String,
    val tipoOperacion: String,
    val regimenAprendizaje: String,
    val duracionMs: Long,
    val actividad: ActividadMedible
)

/** Por qué no se ejecutó un bloque. */
enum class MotivoOmision { CARGANDO, BATERIA_FUERA_DE_BANDA }

data class ResultadoBloque(
    val especificacion: EspecificacionBloque,
    val resumen: ResumenRecursos?,
    val pasadas: Int,
    val omitidoPor: MotivoOmision? = null,
    val error: Throwable? = null
)

/**
 * Ejecuta el protocolo de medición: cada configuración durante varios minutos,
 * de forma sostenida, con línea base y en orden contrabalanceado.
 *
 * POR QUÉ EL PROTOCOLO ES ASÍ. Tres decisiones, y ninguna es de código:
 *
 * 1. **Bloques largos**. El contador de carga del teléfono no resuelve
 *    operaciones de segundos; medir una inferencia por diferencia de carga es
 *    imposible con los instrumentos del aparato. Lo que sí se puede medir es la
 *    TASA de consumo de una actividad sostenida. De ahí que la unidad sea el
 *    bloque y la magnitud reportable sea µAh/h, no µAh por inferencia.
 *
 * 2. **Línea base en cada repetición, no una sola vez**. El consumo en reposo
 *    de un teléfono no es constante: depende de la temperatura, de la señal de
 *    radio y de qué esté haciendo el sistema. Restar una línea base medida hace
 *    dos horas mete esa deriva entera dentro del efecto que se quiere estimar.
 *
 * 3. **Orden contrabalanceado**. La tasa de descarga de una batería de litio
 *    depende de su estado de carga, así que si la configuración A siempre se
 *    mide con la batería al 90% y la B al 40%, la diferencia entre A y B
 *    contiene el efecto del nivel de carga. Invertir el orden en las
 *    repeticiones pares reparte esa deriva entre las condiciones en vez de
 *    dejarla confundida con una de ellas.
 *
 * NO decide si una medición vale: eso lo marca [ResumenRecursos]. Aquí sólo se
 * omiten los bloques que ya se sabe que no valdrían —con el cable puesto o con
 * la batería fuera de la banda— y se deja constancia del motivo.
 */
class ProtocoloDeBloques(
    private val medidor: MedidorDeOperacion,
    private val energia: FuenteEnergia,
    private val bandaBateria: ClosedFloatingPointRange<Float> = BANDA_POR_DEFECTO,
    private val enfriamientoMs: Long = ENFRIAMIENTO_POR_DEFECTO_MS,
    private val reloj: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {

    /**
     * Corre [especificaciones] [repeticiones] veces.
     *
     * @param semilla si se pasa, el orden dentro de cada repetición se baraja
     *   con ella —reproducible— en vez de contrabalancearse por inversión. Con
     *   más de dos condiciones, barajar reparte la deriva mejor que invertir.
     */
    suspend fun ejecutar(
        especificaciones: List<EspecificacionBloque>,
        repeticiones: Int = 1,
        lineaBase: EspecificacionBloque? = null,
        semilla: Long? = null
    ): List<ResultadoBloque> {
        require(especificaciones.isNotEmpty()) { "el protocolo no tiene bloques" }
        require(repeticiones >= 1) { "repeticiones=$repeticiones" }

        val resultados = mutableListOf<ResultadoBloque>()
        for (rep in 0 until repeticiones) {
            val orden = when {
                semilla != null -> especificaciones.shuffled(Random(semilla + rep))
                rep % 2 == 1 -> especificaciones.reversed()
                else -> especificaciones
            }
            // La línea base va DENTRO de cada repetición, junto a las
            // condiciones de las que se va a restar.
            lineaBase?.let { resultados += correrUno(it) }
            for (spec in orden) {
                resultados += correrUno(spec)
            }
        }
        return resultados
    }

    private suspend fun correrUno(spec: EspecificacionBloque): ResultadoBloque {
        omitirPor()?.let { motivo ->
            Log.w(TAG, "bloque '${spec.nombre}' omitido: $motivo")
            return ResultadoBloque(spec, resumen = null, pasadas = 0, omitidoPor = motivo)
        }

        var pasadas = 0
        var fallo: Throwable? = null
        val medicion = medidor.medirConResumen(
            etiqueta = spec.nombre,
            tipoOperacion = spec.tipoOperacion,
            regimenAprendizaje = spec.regimenAprendizaje
        ) {
            val fin = reloj() + spec.duracionMs
            while (reloj() < fin) {
                try {
                    spec.actividad.unaPasada()
                    pasadas++
                } catch (e: Throwable) {
                    // Se corta el bloque pero NO se propaga: el resto del
                    // protocolo puede seguir, y el bloque roto queda registrado
                    // con su error en vez de tumbar la sesión entera.
                    fallo = e
                    Log.e(TAG, "bloque '${spec.nombre}' falló tras $pasadas pasadas", e)
                    break
                }
            }
        }
        // El resumen ya quedó persistido dentro de `medirConResumen`; aquí sólo
        // se propaga para el informe en memoria y para el consumo neto.
        enfriar()
        return ResultadoBloque(
            spec, medicion.resumen, pasadas, omitidoPor = null, error = fallo
        )
    }

    private fun omitirPor(): MotivoOmision? {
        if (energia.estaCargando()) return MotivoOmision.CARGANDO
        val pct = energia.porcentaje()
        // Si el terminal no da porcentaje NO se omite: quedarse sin medir por no
        // poder comprobar una precondición es peor que medir y anotarlo.
        if (pct != null && pct !in bandaBateria) return MotivoOmision.BATERIA_FUERA_DE_BANDA
        return null
    }

    /**
     * Pausa entre bloques.
     *
     * La memoria y la temperatura no vuelven a su sitio en el instante en que
     * termina una actividad: sin esta pausa, el pico de PSS del bloque anterior
     * se cuela en el mínimo del siguiente.
     */
    private suspend fun enfriar() {
        if (enfriamientoMs > 0) delay(enfriamientoMs)
    }

    companion object {
        const val TAG = "ProtocoloDeBloques"

        /**
         * Se mide entre el 20% y el 80%. Fuera de esa banda la curva de descarga
         * del litio deja de ser plana y la tasa de consumo depende más del nivel
         * de carga que de lo que está haciendo el teléfono.
         */
        val BANDA_POR_DEFECTO = 20f..80f

        const val ENFRIAMIENTO_POR_DEFECTO_MS = 30_000L

        /** Bloque de referencia: no hacer nada, para restarlo de los demás. */
        fun reposo(duracionMs: Long, periodoMs: Long = 1_000L) = EspecificacionBloque(
            nombre = MedidorDeOperacion.REPOSO,
            tipoOperacion = MedidorDeOperacion.REPOSO,
            regimenAprendizaje = MedidorDeOperacion.REGIMEN_LOCAL,
            duracionMs = duracionMs,
            actividad = { delay(periodoMs) }
        )
    }
}

/**
 * Consumo NETO de cada condición: su tasa menos la de la línea base de su
 * propia repetición.
 *
 * POR QUÉ EMPAREJADO Y NO CONTRA UNA MEDIA GLOBAL. La línea base se mide en
 * cada repetición precisamente para capturar la deriva del momento —
 * temperatura, señal de radio, nivel de carga—; promediar todas las bases y
 * restar esa media devolvería esa deriva al residuo, que es justo lo que el
 * diseño intenta quitar.
 *
 * Los bloques omitidos y los que no tuvieron contador utilizable no aparecen:
 * un neto que no se pudo calcular es un dato ausente, no un cero.
 */
fun List<ResultadoBloque>.netoPorCondicion(): Map<String, List<Double>> {
    val neto = mutableMapOf<String, MutableList<Double>>()
    var base: ResumenRecursos? = null
    for (r in this) {
        val resumen = r.resumen ?: continue
        if (r.especificacion.tipoOperacion == MedidorDeOperacion.REPOSO) {
            // Sólo sirve de referencia una base que valga: si el terminal
            // estaba cargando durante el reposo, restarla daría un neto
            // inventado.
            base = resumen.takeIf { it.esValida }
            continue
        }
        val b = base ?: continue
        if (!resumen.esValida) continue
        ResumenRecursos.neto(resumen, b)?.let {
            neto.getOrPut(r.especificacion.nombre) { mutableListOf() } += it
        }
    }
    return neto
}
