package com.example.autenticacioncontinua.monitoring

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mide recursos durante un BLOQUE SOSTENIDO de actividad.
 *
 * POR QUÉ POR BLOQUES Y NO POR OPERACIÓN. Es la consecuencia de diseño del
 * diagnóstico del 23/08, y no es sólo una cuestión de código: **no se puede
 * medir el consumo de una operación de 1.5 s por diferencia de carga**, con
 * ningún instrumento del teléfono. La resolución del contador y su cadencia de
 * actualización lo impiden. El protocolo correcto es ejecutar la actividad de
 * forma sostenida durante minutos —una configuración de sensores, un régimen de
 * aprendizaje— y medir el bloque entero.
 *
 * De ahí que esta clase mida por etiqueta y no por llamada, y que el resumen
 * marque explícitamente cuándo el bloque fue demasiado corto para el terminal
 * (`CONTADOR_SIN_VARIACION`) en lugar de devolver un cero que parece un dato.
 *
 * RELOJ MONÓTONO. Se usa `SystemClock.elapsedRealtime`, no
 * `System.currentTimeMillis`: el reloj de pared puede saltar por NTP o por un
 * ajuste manual, y un salto a mitad de un bloque de diez minutos corrompe la
 * duración en silencio.
 */
class MonitorBloque(
    private val energia: FuenteEnergia,
    private val memoria: FuenteMemoria,
    /**
     * Régimen de visibilidad, muestreado junto a la energía y la memoria.
     *
     * Por defecto una fuente constante [EstadoPantalla.DESCONOCIDO], para que
     * las pruebas que no lo ejercitan no tengan que inyectar un doble más. En
     * la aplicación real lo provee el módulo de inyección con la
     * implementación de Android.
     */
    private val estadoPantalla: FuenteEstadoPantalla =
        FuenteEstadoPantalla { EstadoPantalla.DESCONOCIDO },
    /**
     * Método de medida que se EXIGE a cada bloque, o `null` para dejar la
     * elección automática.
     *
     * Por defecto el del estudio. Es la pieza que hace comparables los dos
     * terminales: sin ella, el que tiene mejor contador reportaría por contador
     * y el otro por integración, y `ResumenRecursos.neto` se negaría a restar
     * entre ellos. Ver [MetodoConsumo.DEL_ESTUDIO].
     *
     * `CaracterizacionRecursosTest` construye el monitor con `null` a
     * propósito: ahí el objeto de estudio es justamente qué método sirve en un
     * terminal nuevo, y forzar uno taparía la respuesta.
     */
    private val metodoExigido: MetodoConsumo? = MetodoConsumo.DEL_ESTUDIO,
    private val periodoMuestreoMs: Long = PERIODO_POR_DEFECTO_MS,
    private val alcance: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /**
     * Reloj monótono, inyectable. Por defecto el del sistema; en las pruebas,
     * el virtual de `runTest`, que es lo que permite comprobar en la JVM y en
     * milisegundos la lógica de un bloque de cinco minutos.
     */
    private val reloj: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private data class Bloque(val muestras: MutableList<MuestraRecursos>, val job: Job)

    private val enCurso = mutableMapOf<String, Bloque>()

    /** Toma una muestra ahora mismo. Público porque las pruebas lo usan. */
    fun muestrear(): MuestraRecursos = MuestraRecursos(
        tMs = reloj(),
        cargaMicroAh = energia.cargaMicroAh(),
        corrienteMicroA = energia.corrienteMicroA(),
        pssKb = memoria.pssProcesoKb(),
        cargando = energia.estaCargando(),
        estadoPantalla = estadoPantalla.estado()
    )

    /**
     * Arranca la medición de un bloque. Si ya había uno con la misma etiqueta,
     * se descarta y se avisa: dos bloques simultáneos con el mismo nombre
     * mezclarían sus muestras y el resumen no significaría nada.
     */
    fun iniciar(etiqueta: String) {
        detener(etiqueta)
        val muestras = mutableListOf(muestrear())
        val job = alcance.launch {
            while (isActive) {
                delay(periodoMuestreoMs)
                muestras += muestrear()
            }
        }
        enCurso[etiqueta] = Bloque(muestras, job)
    }

    /**
     * Cierra el bloque y devuelve su resumen, o `null` si esa etiqueta no
     * estaba abierta.
     */
    fun detener(etiqueta: String): ResumenRecursos? {
        val bloque = enCurso.remove(etiqueta) ?: return null
        bloque.job.cancel()
        // Una muestra final tras cancelar: cierra el intervalo en el instante
        // real de parada y no en el último tic del muestreo.
        bloque.muestras += muestrear()
        return ResumenRecursos.desde(etiqueta, bloque.muestras, metodoExigido)
    }

    /** Etiquetas de los bloques abiertos. Para diagnóstico y pruebas. */
    fun abiertos(): Set<String> = enCurso.keys.toSet()

    companion object {
        /**
         * 500 ms. Suficientemente fino para captar el pico de memoria al cargar
         * el modelo TFLite, y suficientemente grueso para que el propio muestreo
         * no distorsione lo que mide: `Debug.getMemoryInfo` no es gratis.
         */
        const val PERIODO_POR_DEFECTO_MS = 500L

        /**
         * Duración mínima recomendada de un bloque: cinco minutos.
         *
         * YA NO LA FIJA EL CONTADOR DE CARGA. Medido sin cable en el Redmi
         * 23129RA5FL el 24/08: 295 lecturas del contador en 60 s y **cero
         * cambios**. Ese instrumento no resuelve un bloque de ninguna duración
         * razonable, y esperar a que se mueva habría exigido bloques de horas.
         * El consumo se obtiene integrando la corriente instantánea, que a 2-4
         * Hz da una cifra estable en veinte segundos: en ese mismo terminal,
         * 147.9 mA en reposo contra 484.5 mA bajo carga computacional.
         *
         * LOS CINCO MINUTOS SE MANTIENEN POR OTRA RAZÓN, que no es del
         * instrumento sino del sujeto medido: el teléfono no está quieto. La
         * radio, el recolector de basura, otras aplicaciones y el gobernador de
         * frecuencia meten variación de segundos a decenas de segundos, y un
         * bloque de veinte segundos mide tanto eso como la actividad que se
         * quiere medir. Cinco minutos promedian esa variación.
         *
         * Sigue siendo una recomendación de protocolo, no una restricción del
         * código.
         */
        const val DURACION_MINIMA_RECOMENDADA_MS = 5 * 60 * 1000L
    }
}
