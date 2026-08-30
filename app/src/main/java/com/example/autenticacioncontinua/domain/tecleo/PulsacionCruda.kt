package com.example.autenticacioncontinua.domain.tecleo

/** Qué hace un dedo sobre una tecla. */
enum class FaseDePulsacion { ABAJO, ARRIBA }

/**
 * Una pulsación tal y como sale de la pantalla, antes de saber si acierta.
 *
 * POR QUÉ ESTE TIPO EXISTE, Y POR QUÉ HAY UN TECLADO PROPIO DETRÁS.
 *
 * La fase 3 exige registrar `down`, `up`, coordenadas, presión y área. **Con el
 * teclado del sistema eso es imposible**, y conviene dejarlo escrito porque
 * parece un detalle de implementación y es una decisión de diseño del estudio:
 *
 * - los teclados software de Android no envían `KeyEvent` con `down`/`up` por
 *   tecla; entregan texto ya compuesto por `InputConnection.commitText`. Un
 *   `TextWatcher` ve aparecer caracteres, no dedos;
 * - por tanto no hay **tiempo de permanencia** (`up − down` de una misma
 *   tecla), que es la magnitud de la dinámica de tecleo que menos depende del
 *   texto que se copia;
 * - y no hay presión ni área de contacto, porque el `MotionEvent` se lo queda
 *   el proceso del teclado.
 *
 * De ahí que el minijuego dibuje **su propio teclado**. Y eso, que nació como
 * una obligación técnica, resuelve además un problema del diseño experimental:
 * con el teclado del sistema, **cada terminal tiene su propio tamaño y
 * separación de teclas**, de modo que la geometría de la pulsación sería otra
 * variable de dispositivo confundida con la persona — justo lo que el diseño
 * cruzado existe para evitar. Con teclado propio, la disposición es idéntica en
 * los dos aparatos y entre todos los participantes.
 *
 * Efecto secundario favorable: la lista de verificación previa deja de tener
 * que comprobar predicción, autocorrección, deslizamiento ni idioma del
 * teclado. No hay teclado del sistema que configurar.
 *
 * Coste, y hay que declararlo: teclear en un teclado desconocido no es teclear
 * en el propio. El estudio mide dinámica de tecleo **en un teclado neutro**, no
 * en el que el participante usa a diario. Para comparar personas entre sí —que
 * es lo que hace falta— es lo correcto; para predecir el rendimiento en el
 * teclado habitual de cada uno, no.
 */
data class PulsacionCruda(
    val fase: FaseDePulsacion,

    /** Carácter que la tecla produce. Vacío en teclas de función. */
    val caracter: String,

    /** `true` si es la tecla de retroceso. */
    val esRetroceso: Boolean = false,

    /** Reloj de pared en ms, coherente con el resto de tablas. */
    val tMs: Long,

    /** Coordenadas dentro de la tecla, en píxeles independientes de densidad. */
    val x: Float? = null,
    val y: Float? = null,

    /**
     * `MotionEvent.getPressure()`. Hay terminales que devuelven 1.0 constante.
     * Si es el caso, [DetectorDeConstante] lo detecta y queda registrado.
     */
    val presion: Float? = null,

    /** `MotionEvent.getTouchMajor()`: eje mayor del área de contacto. */
    val area: Float? = null
)

/**
 * Comprueba si un canal entrega siempre el mismo valor.
 *
 * POR QUÉ HACE FALTA. La presión y el área son opcionales en Android y hay
 * terminales que devuelven una constante: presión 1.0 siempre, o un área
 * cuantizada a un único valor. Guardar esa constante como si fuera una medida
 * haría creer al análisis que hay una variable donde sólo hay una decisión del
 * fabricante — y un modelo que la reciba puede incluso usarla como sesgo.
 *
 * No basta con mirar dos lecturas: hay pulsaciones que coinciden por azar. Se
 * acumulan [MINIMO_MUESTRAS] antes de dictaminar, y hasta entonces el veredicto
 * es «todavía no se sabe», que es distinto de «es constante».
 */
class DetectorDeConstante(private val nombre: String) {

    private var primero: Float? = null
    private var n = 0
    private var vario = false
    private var minimo = Float.MAX_VALUE
    private var maximo = Float.MIN_VALUE

    fun observar(valor: Float?) {
        if (valor == null) return
        n++
        if (valor < minimo) minimo = valor
        if (valor > maximo) maximo = valor
        val p = primero
        if (p == null) primero = valor
        else if (!vario && kotlin.math.abs(valor - p) > EPSILON) vario = true
    }

    /** `null` mientras no haya muestras suficientes para dictaminar. */
    val esConstante: Boolean?
        get() = when {
            n < MINIMO_MUESTRAS -> null
            else -> !vario
        }

    val muestras: Int get() = n
    val rango: ClosedFloatingPointRange<Float>?
        get() = if (n == 0) null else minimo..maximo

    /** Una línea para el registro y para la sección de limitaciones. */
    fun informe(): String = when (esConstante) {
        null -> "$nombre: sin datos suficientes ($n muestras)"
        true -> "$nombre: CONSTANTE en $minimo tras $n muestras — este terminal " +
            "no lo mide; debe quedar en la memoria como limitacion"
        false -> "$nombre: varia en [$minimo, $maximo] sobre $n muestras"
    }

    companion object {
        /**
         * 30 pulsaciones. Con menos, un participante que pulse muy uniforme
         * podría parecer un terminal que no mide.
         */
        const val MINIMO_MUESTRAS = 30

        /**
         * La presión llega como flotante de 32 bits ya cuantizado por el
         * controlador; este umbral distingue «el mismo valor» del ruido de
         * representación sin tragarse una variación real, que es de centésimas.
         */
        const val EPSILON = 1e-4f
    }
}
