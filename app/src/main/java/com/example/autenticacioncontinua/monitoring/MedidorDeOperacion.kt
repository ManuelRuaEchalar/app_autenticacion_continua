package com.example.autenticacioncontinua.monitoring

import android.util.Log
import com.example.autenticacioncontinua.domain.repository.IRegistroMediciones
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Lo que devolvió la operación, junto al resumen del bloque que la midió. */
data class MedicionDe<T>(val valor: T, val resumen: ResumenRecursos?)

/**
 * Une las tres piezas de la medición —bloque de recursos, latencias y
 * persistencia— detrás de una sola llamada.
 *
 * POR QUÉ EXISTE. Sin esto, cada punto que quisiera medirse tendría que
 * inyectar [MonitorBloque], [Cronometro] y [IRegistroMediciones], abrir el
 * bloque, acordarse de cerrarlo aunque la operación lance, resumir la latencia
 * y guardar dos filas. Repetido en inferencia, entrenamiento local y ronda
 * federada son tres copias del mismo protocolo y tres sitios donde olvidarse
 * de un `finally`.
 *
 * QUÉ NO HACE. No decide si la medición vale: eso es de [ResumenRecursos], que
 * marca los motivos de invalidez. Aquí se guarda todo, válido o no, porque
 * cuántas mediciones se perdieron y por qué es parte del resultado.
 *
 * LAS ETIQUETAS SON CONSTANTES, no cadenas sueltas en los llamadores: el
 * análisis agrupa por ellas y un `"ronda_FL"` contra un `"ronda_fl"` partiría
 * una celda del diseño en dos sin que nada fallara.
 */
class MedidorDeOperacion(
    private val monitor: MonitorBloque,
    private val cronometro: Cronometro,
    private val registro: IRegistroMediciones,
    /**
     * Qué configuración de sensores etiqueta cada fila.
     *
     * ES UNA FUNCIÓN Y NO UN VALOR, y la diferencia importa. Con un `String`
     * inyectado, el medidor se quedaba con la configuración que hubiera al
     * construirse —un `single` de Koin, o sea al arrancar la aplicación— y
     * cambiarla a mitad de campaña no llegaba hasta aquí: las filas seguían
     * saliendo con la anterior. Eso no falla ni avisa; produce filas plausibles
     * y mal etiquetadas, que es peor. Consultándola en cada medición, la fila
     * lleva la que de verdad estaba activa.
     */
    private val configSensores: () -> String,
    /** Ver la nota de `MonitorBloque.reloj`. Nanosegundos, monótono. */
    private val relojNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() }
) {

    /**
     * Ejecuta [bloque] midiendo energía y memoria durante toda su duración, y
     * su latencia, y persiste ambas cosas.
     *
     * El bloque se cierra en `finally`: una ronda federada que falla a mitad
     * sigue habiendo consumido batería, y perder esa medida sesgaría la media
     * hacia las rondas que salieron bien.
     *
     * @param etiqueta identifica ESTA ejecución concreta (`ronda_fl_7`). Tiene
     *   que ser única mientras el bloque esté abierto o dos bloques mezclarían
     *   sus muestras.
     * @param tipoOperacion la CLASE de operación (`ronda_fl`). Es la clave con
     *   la que se agrupan las latencias: si se usara la etiqueta, cada ronda
     *   sería una serie de un solo elemento y no habría mediana que reportar.
     */
    suspend fun <T> medir(
        etiqueta: String,
        tipoOperacion: String,
        regimenAprendizaje: String,
        bloque: suspend () -> T
    ): T = medirConResumen(etiqueta, tipoOperacion, regimenAprendizaje, bloque).valor

    /**
     * Igual que [medir], pero devuelve además el resumen del bloque.
     *
     * Existen las dos porque los llamadores son de dos clases. Al cliente
     * federado el resumen no le sirve de nada —ya está persistido— y pedirle
     * que desenvuelva un par en cada ronda sólo añade ruido. Al protocolo de
     * bloques sí le hace falta, porque compone el informe en memoria y calcula
     * el consumo neto contra la línea base de su propia repetición.
     */
    suspend fun <T> medirConResumen(
        etiqueta: String,
        tipoOperacion: String,
        regimenAprendizaje: String,
        bloque: suspend () -> T
    ): MedicionDe<T> {
        monitor.iniciar(etiqueta)
        val t0 = relojNanos()
        var resumen: ResumenRecursos? = null
        val valor: T
        try {
            valor = bloque()
        } finally {
            cronometro.registrarNanos(
                tipoOperacion, relojNanos() - t0
            )
            // `detener` TIENE que ejecutarse aunque el bloque lance: cancela la
            // corrutina que está muestreando cada 500 ms. Sin esto, una ronda
            // que falla deja un muestreador vivo para siempre, gastando la
            // batería que este módulo existe para medir.
            resumen = monitor.detener(etiqueta)
            if (resumen != null) {
                // NonCancellable: si la sesión federada se canceló, una llamada
                // suspendida normal dentro del `finally` lanzaría de inmediato y
                // la medición del bloque que sí llegó a correr se perdería.
                withContext(NonCancellable) {
                    registro.registrarBloque(
                        resumen!!, tipoOperacion, configSensores(), regimenAprendizaje
                    )
                }
                if (!resumen!!.esValida) {
                    Log.i(TAG, "bloque '$etiqueta' no válido: ${resumen!!.invalidez}")
                }
            }
        }
        return MedicionDe(valor, resumen)
    }

    /**
     * Vuelca a la base los resúmenes de latencia acumulados en el cronómetro y
     * lo vacía.
     *
     * Se llama al terminar una sesión federada o una sesión controlada, no en
     * cada operación: una serie de dos inferencias no tiene mediana que
     * signifique nada, y guardar una fila por inferencia llenaría la tabla sin
     * añadir información.
     */
    suspend fun volcarLatencias(
        regimenAprendizaje: String,
        /**
         * Régimen de visibilidad al que se ATRIBUYE la serie volcada.
         *
         * Se pasa y no se lee aquí a propósito. El cronómetro acumula a lo
         * largo de muchas operaciones y se vuelca de golpe, así que leer el
         * estado en este instante daría el que hubiera al final del volcado, no
         * el que hubo mientras se medía: sería un valor con aspecto de dato y
         * sin respaldo. Quien vuelca sí sabe bajo qué protocolo corrió —durante
         * una campaña de medición el estado es constante por construcción— y es
         * quien debe declararlo. En uso libre se deja [EstadoPantalla
         * .DESCONOCIDO], que es la verdad.
         */
        estadoPantalla: EstadoPantalla = EstadoPantalla.DESCONOCIDO
    ): Int {
        val resumenes = cronometro.resumenes()
        for (r in resumenes) {
            registro.registrarLatencia(r, configSensores(), regimenAprendizaje, estadoPantalla)
        }
        cronometro.limpiar()
        return resumenes.size
    }

    companion object {
        const val TAG = "MedidorDeOperacion"

        const val INFERENCIA = "inferencia"
        const val INFERENCIA_VENTANA = "inferencia_ventana"
        const val ENTRENAMIENTO_LOCAL = "entrenamiento_local"
        const val RONDA_FL = "ronda_fl"
        const val EVALUACION_FL = "evaluacion_fl"
        const val REPOSO = "reposo"
        const val SESION_CONTROLADA = "sesion_controlada"

        const val REGIMEN_LOCAL = "local"
        const val REGIMEN_FEDERADO = "federado"
        const val REGIMEN_GLOBAL = "global"
    }
}
