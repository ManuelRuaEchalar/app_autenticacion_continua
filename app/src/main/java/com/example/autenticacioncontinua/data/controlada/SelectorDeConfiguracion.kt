package com.example.autenticacioncontinua.data.controlada

import android.content.Context
import android.util.Log
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
import com.example.autenticacioncontinua.domain.sensor.ProveedorDeConfiguracion

/**
 * Cuál de las configuraciones de sensores está activa en este terminal.
 *
 * POR QUÉ EXISTE. Hasta el 06/09 la configuración salía de `sensorConfig` del
 * manifiesto del modelo, un valor fijo por compilación. Recorrer A, B y C
 * exigía tres compilaciones distintas, y con eso el contrabalanceo del
 * protocolo de bloques —que reparte la deriva de la batería entre condiciones
 * invirtiendo el orden en las repeticiones pares— no servía de nada: no se
 * pueden alternar condiciones dentro de una campaña si cambiar de condición
 * obliga a reinstalar.
 *
 * ES ESTADO DE PROTOCOLO, NO UNA PREFERENCIA DE USUARIO. No va en ningún ajuste
 * visible al participante. La fija el investigador antes de una campaña de
 * medición, igual que la etiqueta A/B del terminal, y por el mismo motivo se
 * persiste: si el proceso muere a mitad de campaña, al volver tiene que seguir
 * en la misma condición o los bloques de antes y después del reinicio quedarían
 * mezclados bajo la misma etiqueta.
 *
 * NO CAMBIA EL MODELO. Cambiar de configuración cambia QUÉ SENSORES SE
 * REGISTRAN y CÓMO SE ETIQUETA cada fila de medición. El `.tflite` sigue siendo
 * el del manifiesto, porque los modelos de A, C y D todavía no existen: hay que
 * preentrenar uno por configuración, y cada uno tiene una dimensión de entrada
 * distinta. Ver [advertirSiNoCasaConElModelo].
 */
interface SelectorDeConfiguracion : ProveedorDeConfiguracion {

    /**
     * La activa. Al escribir se persiste.
     *
     * @throws IllegalArgumentException si la clave no corresponde a ninguna
     *   configuración conocida.
     */
    var configuracion: ConfiguracionSensores

    override fun activa(): ConfiguracionSensores = configuracion
}

/** La de verdad: persiste en las preferencias del terminal. */
class SelectorEnPreferencias(context: Context) : SelectorDeConfiguracion {

    private val prefs =
        context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    override var configuracion: ConfiguracionSensores
        get() {
            val guardada = prefs.getString(CLAVE, null) ?: return ConfiguracionSensores.POR_DEFECTO
            // Una clave desconocida en las preferencias significa que alguien
            // instaló una versión con otros nombres. Se avisa y se cae en la
            // de por defecto en vez de reventar al arrancar: perder la
            // configuración es recuperable, no poder abrir la aplicación en
            // mitad de una visita no lo es.
            return ConfiguracionSensores.porClave(guardada) ?: run {
                Log.w(TAG, "configuracion '$guardada' desconocida; se usa la de por defecto")
                ConfiguracionSensores.POR_DEFECTO
            }
        }
        set(valor) {
            prefs.edit().putString(CLAVE, valor.clave).apply()
            Log.i(TAG, "configuracion de sensores -> ${valor.clave}")
        }

    private companion object {
        const val TAG = "SelectorDeConfiguracion"
        const val PREFERENCIAS = "configuracion_sensores"
        const val CLAVE = "activa"
    }
}

/**
 * Deja constancia si la configuración activa no coincide con la del modelo
 * cargado.
 *
 * NO IMPIDE NADA, Y ES DELIBERADO. Durante las campañas de recursos se recorren
 * A, C y D con el modelo de B cargado, porque las mediciones que interesan ahí
 * —coste de la captura, de una ronda de entrenamiento, de la línea base— no
 * dependen de que el modelo case con los canales que se están registrando. Lo
 * que NO se puede hacer es reportar EFECTIVIDAD en esas condiciones: el modelo
 * estaría recibiendo un número de canales distinto del que espera.
 *
 * Se registra en el log para que quede en el informe de la campaña, y para que
 * al analizar se pueda separar "esta fila se midió con el modelo casado" de
 * "esta no".
 */
fun advertirSiNoCasaConElModelo(
    activa: ConfiguracionSensores,
    claveDelModelo: String,
    log: (String) -> Unit = { Log.w("ConfiguracionSensores", it) }
): Boolean {
    val casa = activa.clave == claveDelModelo
    if (!casa) {
        log(
            "la configuracion activa es '${activa.clave}' y el modelo cargado es " +
                "'$claveDelModelo': las mediciones de RECURSOS siguen valiendo, " +
                "las de EFECTIVIDAD no."
        )
    }
    return casa
}
