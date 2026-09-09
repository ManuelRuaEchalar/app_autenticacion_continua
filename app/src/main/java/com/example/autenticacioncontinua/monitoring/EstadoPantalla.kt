package com.example.autenticacioncontinua.monitoring

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * En qué régimen de visibilidad corría la aplicación mientras se medía.
 *
 * POR QUÉ ES UN FACTOR DEL DISEÑO Y NO UN DETALLE. Ejecutar trabajo en segundo
 * plano mientras otra aplicación ocupa el primer plano sale considerablemente
 * más barato que ejecutarlo con el teléfono en reposo: la pantalla ya está
 * encendida, el procesador ya está despierto y la radio ya está activa, de modo
 * que el coste marginal que se le atribuye a nuestro trabajo es sólo el
 * incremento sobre un dispositivo que ya estaba consumiendo. La literatura de
 * co-ejecución lo mide como un "descuento energético" (IEEE Trans. Mobile
 * Computing, 2024).
 *
 * Consecuencia directa para este estudio: **dos bloques medidos en estados
 * distintos no son comparables**, igual que no lo son dos bloques medidos con
 * instrumentos distintos. Sin esta columna, la diferencia entre la
 * configuración A y la B podría ser en realidad la diferencia entre haberlas
 * medido con la pantalla encendida y con la pantalla apagada.
 *
 * Y hay un motivo de fondo: el régimen REAL de la autenticación continua es
 * [PANTALLA_APAGADA] —el teléfono en el bolsillo—, que es justamente el estado
 * en que el descuento de co-ejecución no existe y el coste es máximo. Medir
 * sólo con la aplicación en primer plano daría la cifra más favorable y menos
 * representativa.
 */
enum class EstadoPantalla {

    /** Pantalla encendida y la interfaz de ESTA aplicación visible. */
    PRIMER_PLANO,

    /**
     * Pantalla encendida pero nuestra interfaz no visible.
     *
     * Incluye el caso de un servicio en primer plano —el recolector— corriendo
     * mientras el usuario usa otra aplicación. Ver la nota de
     * [FuenteEstadoPantallaAndroid] sobre por qué un servicio en primer plano
     * NO cuenta como [PRIMER_PLANO].
     */
    SEGUNDO_PLANO,

    /**
     * Pantalla apagada. Es el régimen real de la autenticación continua y el
     * más caro, porque no hay ninguna actividad ajena cuyo consumo ya esté
     * pagado.
     */
    PANTALLA_APAGADA,

    /**
     * El estado CAMBIÓ durante el bloque.
     *
     * No es un fallo de lectura: es un bloque que abarca dos regímenes y cuyo
     * consumo es una mezcla en proporción desconocida. Se conserva la medición
     * —el consumo total sigue siendo un dato— pero queda fuera de cualquier
     * análisis que compare estados. Lo produce únicamente
     * [ResumenRecursos.desde]; ninguna muestra individual vale [MIXTO].
     */
    MIXTO,

    /** No se pudo determinar. Distinto de [MIXTO]: aquí falló la lectura. */
    DESCONOCIDO;

    companion object {

        /**
         * Estado de un bloque a partir de los estados de sus muestras.
         *
         * Unanimidad o [MIXTO]. No se toma la moda ni el estado de la primera
         * muestra: un bloque de cinco minutos que empieza en primer plano y
         * termina con la pantalla apagada no "es" ninguno de los dos, y
         * asignarle el mayoritario metería la diferencia entre regímenes dentro
         * del efecto que se quiere estimar.
         */
        fun deMuestras(estados: List<EstadoPantalla>): EstadoPantalla = when {
            estados.isEmpty() -> DESCONOCIDO
            estados.distinct().size == 1 -> estados.first()
            else -> MIXTO
        }
    }
}

/**
 * Lectura cruda del régimen de visibilidad. Interfaz para poder sustituirla en
 * las pruebas, igual que [FuenteEnergia] y [FuenteMemoria].
 */
fun interface FuenteEstadoPantalla {
    fun estado(): EstadoPantalla
}

/**
 * Implementación sobre Android.
 *
 * DOS LECTURAS, Y EL ORDEN IMPORTA. Primero la pantalla: si está apagada, el
 * régimen es [EstadoPantalla.PANTALLA_APAGADA] con independencia de la
 * importancia del proceso, porque un servicio en primer plano con la pantalla
 * apagada sigue siendo el caso caro. Sólo si la pantalla está encendida tiene
 * sentido preguntar si somos nosotros los que estamos delante.
 *
 * POR QUÉ UN SERVICIO EN PRIMER PLANO NO CUENTA COMO PRIMER PLANO. Nuestro
 * recolector corre como servicio en primer plano para que el sistema no lo
 * mate, y eso sitúa al proceso en `IMPORTANCE_FOREGROUND_SERVICE` (125) aunque
 * el usuario esté en otra aplicación. Si el umbral fuera "importancia de
 * servicio en primer plano o mejor", TODA la recolección quedaría etiquetada
 * como [EstadoPantalla.PRIMER_PLANO] y la distinción no separaría nada. El
 * umbral es `IMPORTANCE_FOREGROUND` (100), que significa interfaz visible.
 *
 * COSTE. Se llama una vez por muestra, es decir cada 500 ms durante el bloque.
 * `getMyMemoryState` rellena una estructura que se reutiliza —igual que en
 * [FuenteMemoriaAndroid]— para no asignar un objeto por muestra en el módulo
 * que precisamente mide el consumo.
 */
class FuenteEstadoPantallaAndroid(context: Context) : FuenteEstadoPantalla {

    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val info = ActivityManager.RunningAppProcessInfo()

    override fun estado(): EstadoPantalla {
        val pantallaEncendida = pantallaEncendida() ?: return EstadoPantalla.DESCONOCIDO
        if (!pantallaEncendida) return EstadoPantalla.PANTALLA_APAGADA

        return runCatching {
            ActivityManager.getMyMemoryState(info)
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                EstadoPantalla.PRIMER_PLANO
            } else {
                EstadoPantalla.SEGUNDO_PLANO
            }
        }.getOrDefault(EstadoPantalla.DESCONOCIDO)
    }

    /**
     * `null` si no se pudo leer.
     *
     * Se consulta el estado del display por defecto y no `PowerManager
     * .isInteractive` porque `isInteractive` devuelve true también en estados
     * de baja potencia en los que la pantalla no está realmente encendida.
     * `STATE_DOZE` y `STATE_DOZE_SUSPEND` —la pantalla ambiente— se cuentan
     * como apagada: el panel muestra un mínimo de píxeles y el régimen de
     * consumo es el de reposo, no el de uso.
     */
    private fun pantallaEncendida(): Boolean? {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return when (display.state) {
            Display.STATE_ON, Display.STATE_VR -> true
            Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> false
            else -> null
        }
    }
}
