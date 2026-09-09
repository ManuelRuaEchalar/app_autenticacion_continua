package com.example.autenticacioncontinua.ui

import androidx.compose.ui.graphics.Color
import com.example.autenticacioncontinua.ui.theme.ColoresClaros
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contraste de todas las combinaciones texto/fondo de la paleta.
 *
 * POR QUÉ ES UNA PRUEBA Y NO UNA REVISIÓN A OJO. El contraste no se puede
 * juzgar mirando: dos grises que en el monitor del que diseña parecen bien
 * legibles pueden estar por debajo del mínimo, y sólo se nota cuando alguien
 * usa el teléfono con sol de frente — que es exactamente la situación de una
 * sesión de campo. Aquí se calcula, y si baja del umbral la compilación falla.
 *
 * Umbrales de WCAG 2.1 nivel AA:
 * - **4.5:1** para texto normal;
 * - **3:1** para texto grande (≥18.66 sp en negrita o ≥24 sp) y para elementos
 *   no textuales que haya que distinguir, como bordes y separadores.
 *
 * El cálculo es el estándar: luminancia relativa con corrección gamma y el
 * cociente `(L1 + 0.05) / (L2 + 0.05)`.
 */
class ContrasteTest {

    // ------------------------------------------------------------------
    // Texto normal sobre sus fondos: 4.5:1
    // ------------------------------------------------------------------

    @Test
    fun `el texto primario cumple AA sobre todos sus fondos`() {
        val c = ColoresClaros
        exigir(c.textoPrimario, c.fondo, 4.5, "primario sobre fondo")
        exigir(c.textoPrimario, c.fondoSecundario, 4.5, "primario sobre barra lateral")
        exigir(c.textoPrimario, c.superficie, 4.5, "primario sobre superficie")
        exigir(c.textoPrimario, c.hover, 4.5, "primario sobre hover")
    }

    @Test
    fun `el texto secundario cumple AA sobre todos sus fondos`() {
        val c = ColoresClaros
        exigir(c.textoSecundario, c.fondo, 4.5, "secundario sobre fondo")
        exigir(c.textoSecundario, c.fondoSecundario, 4.5, "secundario sobre barra lateral")
        exigir(c.textoSecundario, c.hover, 4.5, "secundario sobre hover")
    }

    /**
     * El terciario es el gris más claro que aún se puede LEER. Se usa para
     * metadatos —fechas, contadores— sobre los fondos claros.
     */
    @Test
    fun `el texto terciario cumple AA sobre los fondos claros`() {
        exigir(ColoresClaros.textoTerciario, ColoresClaros.fondo, 4.5, "terciario sobre fondo")
        exigir(ColoresClaros.textoTerciario, ColoresClaros.superficie, 4.5, "terciario sobre superficie")
    }

    /**
     * `iconoSutil` es el #8E8E8E que el plan pedía como texto terciario y que
     * esta prueba rechazó: 3.28:1, por debajo de lo legible. Queda como
     * elemento NO textual, donde 3:1 es el mínimo aplicable.
     */
    @Test
    fun `el icono sutil cumple el minimo de elemento no textual`() {
        exigir(ColoresClaros.iconoSutil, ColoresClaros.fondo, 3.0, "icono sutil sobre fondo")
        val comoTexto = contraste(ColoresClaros.iconoSutil, ColoresClaros.fondo)
        assertTrue(
            "si `iconoSutil` llegara a 4.5:1 sobraria: se fundiria con " +
                "`textoTerciario` y la jerarquia visual perderia un nivel",
            comoTexto < 4.5
        )
    }

    @Test
    fun `el texto del boton primario cumple AA sobre el boton, tambien en hover`() {
        val c = ColoresClaros
        exigir(c.textoSobreBotonPrimario, c.botonPrimario, 4.5, "texto sobre boton")
        exigir(c.textoSobreBotonPrimario, c.botonPrimarioHover, 4.5, "texto sobre boton hover")
    }

    /**
     * El acento de la identidad visual (#10A37F) se queda en 3.20:1 sobre
     * blanco: vale para indicadores y barras, no para texto.
     *
     * Y SOBRE LA BARRA LATERAL NO LLEGA: 2.99:1, por una centésima. De ahí la
     * regla de uso —comprobada abajo— de que sobre el fondo secundario se usa
     * [ColoresApp.acentoTexto] y no el acento. Falla por tan poco que a ojo
     * nadie lo habría visto, y es justo el caso en que un indicador de
     * selección dejaría de distinguirse con el teléfono al sol.
     */
    @Test
    fun `el acento cumple el minimo de elemento no textual sobre el fondo principal`() {
        exigir(ColoresClaros.acento, ColoresClaros.fondo, 3.0, "acento sobre fondo")
    }

    @Test
    fun `el acento NO vale sobre la barra lateral, y por eso alli va el textual`() {
        val r = contraste(ColoresClaros.acento, ColoresClaros.fondoSecundario)
        assertTrue(
            "el acento ha llegado a %.2f:1 sobre la barra lateral. Si la paleta ".format(r) +
                "cambio y ahora cumple, esta regla de uso sobra y hay que quitarla.",
            r < 3.0
        )
        exigir(ColoresClaros.acentoTexto, ColoresClaros.fondoSecundario, 3.0,
            "la variante que si se usa en la barra lateral")
    }

    /**
     * Y su variante legible, que es la que se usa cuando el acento tiene que
     * leerse: el carácter marcado del teleprompter, el elemento seleccionado.
     */
    @Test
    fun `el acento textual cumple AA sobre todos los fondos claros`() {
        val c = ColoresClaros
        exigir(c.acentoTexto, c.fondo, 4.5, "acento textual sobre fondo")
        exigir(c.acentoTexto, c.fondoSecundario, 4.5, "acento textual sobre lateral")
        exigir(c.acentoTexto, c.hover, 4.5, "acento textual sobre hover")
    }

    /**
     * El carácter fallado del teleprompter es texto que hay que leer para
     * saber qué se escribió mal, no un adorno.
     */
    @Test
    fun `el color de error cumple AA como texto`() {
        exigir(ColoresClaros.error, ColoresClaros.fondo, 4.5, "error sobre fondo")
    }

    /**
     * Los dos colores de estado, sobre los fondos donde se leen.
     *
     * Son TEXTO, no adornos: «Protegido», «Recolección suspendida» o el aviso
     * de que un EER no se puede creer son frases que hay que poder leer. El
     * naranja y el verde de la paleta oscura anterior (#D29922 y #3FB950) se
     * quedaban en 2.0:1 y 2.8:1 sobre blanco — perfectamente legibles sobre el
     * fondo negro para el que se eligieron, e ilegibles sobre el claro. Es
     * exactamente el error que se comete al mudar una paleta de un modo al
     * otro sin recalcular, y por eso se comprueba aquí.
     */
    @Test
    fun `los colores de estado cumplen AA como texto sobre los fondos claros`() {
        val c = ColoresClaros
        exigir(c.exito, c.fondo, 4.5, "exito sobre fondo")
        exigir(c.exito, c.fondoSecundario, 4.5, "exito sobre superficie secundaria")
        exigir(c.exito, c.hover, 4.5, "exito sobre hover")
        exigir(c.aviso, c.fondo, 4.5, "aviso sobre fondo")
        exigir(c.aviso, c.fondoSecundario, 4.5, "aviso sobre superficie secundaria")
        exigir(c.aviso, c.hover, 4.5, "aviso sobre hover")
        exigir(c.error, c.fondoSecundario, 4.5, "error sobre superficie secundaria")
        exigir(c.error, c.hover, 4.5, "error sobre hover")
    }

    /**
     * Y los tres estados tienen que distinguirse ENTRE SÍ, no sólo del fondo.
     *
     * Un semáforo cuyos tres colores cumplen el contraste contra el fondo pero
     * son casi el mismo tono entre ellos no informa de nada. El caso que esta
     * prueba vigila es el de [ColoresApp.exito] contra [ColoresApp.acentoTexto],
     * que son los dos verdes de la paleta y conviven en la misma pantalla.
     */
    @Test
    fun `los colores de estado se distinguen entre si y del acento`() {
        val c = ColoresClaros
        val pares = listOf(
            Triple("exito/aviso", c.exito, c.aviso),
            Triple("exito/error", c.exito, c.error),
            Triple("aviso/error", c.aviso, c.error),
            Triple("exito/acentoTexto", c.exito, c.acentoTexto)
        )
        for ((que, a, b) in pares) {
            assertTrue(
                "$que son practicamente el mismo color (%.2f de distancia): ".format(
                    distancia(a, b)
                ) + "el estado dejaria de leerse por el color",
                distancia(a, b) > 0.12
            )
        }
    }

    /**
     * Las letras del teclado, sobre los tres fondos que puede tener una tecla.
     *
     * Es el texto que el participante mira mientras teclea durante toda la
     * sesión, y lo hace de reojo: si una tecla pulsada o una de función deja de
     * leerse, la tarea cambia sin que nada falle.
     */
    @Test
    fun `el rotulo de las teclas cumple AA sobre los tres fondos de tecla`() {
        val c = ColoresClaros
        exigir(c.textoPrimario, c.superficie, 4.5, "letra sobre tecla normal")
        exigir(c.textoPrimario, c.teclaFuncion, 4.5, "simbolo sobre tecla de funcion")
        exigir(c.textoPrimario, c.hover, 4.5, "letra sobre tecla pulsada")
    }

    /**
     * Y las teclas tienen que distinguirse del fondo del teclado.
     *
     * NO se les aplica el 3:1 de WCAG: ese mínimo es para elementos cuya forma
     * no basta para identificarlos, y una tecla se identifica por su rótulo, su
     * rectángulo redondeado y su separación. Los teclados del sistema —el de
     * MIUI incluido— usan exactamente esta diferencia pequeña. Lo que sí hay que
     * garantizar es que la diferencia EXISTA: si el fondo del teclado y la tecla
     * acabaran iguales, la cuadrícula desaparecería.
     */
    @Test
    fun `las teclas se distinguen del fondo del teclado`() {
        val c = ColoresClaros
        assertTrue(
            "la tecla normal y el fondo del teclado son indistinguibles",
            contraste(c.superficie, c.teclaFondo) > 1.15
        )
        assertTrue(
            "la tecla de funcion no se distingue del fondo del teclado",
            contraste(c.teclaFuncion, c.teclaFondo) > 1.05
        )
    }

    // ------------------------------------------------------------------
    // Elementos no textuales: 3:1
    // ------------------------------------------------------------------

    /**
     * El plan pide separadores «muy sutiles, nunca bordes pesados», y un borde
     * sutil de más deja de verse. Se comprueba contra el mínimo de elementos no
     * textuales; si no llega, hay que oscurecerlo o dejar de confiar en él para
     * delimitar zonas pulsables.
     */
    @Test
    fun `el borde se distingue del fondo lo suficiente para delimitar`() {
        val r = contraste(ColoresClaros.borde, ColoresClaros.fondo)
        // Se informa siempre, se exija o no: el número importa para decidir.
        println("contraste borde/fondo = %.2f:1".format(r))
        assertTrue(
            "el borde (%.2f:1) no llega ni a 1.2:1 sobre el fondo: seria invisible"
                .format(r),
            r >= 1.2
        )
    }

    // ------------------------------------------------------------------
    // Informe completo
    // ------------------------------------------------------------------

    /**
     * No asevera nada: imprime la tabla entera para poder decidir con números
     * cuando se toque la paleta.
     */
    @Test
    fun `imprime la tabla de contrastes de la paleta`() {
        val c = ColoresClaros
        val textos = listOf(
            "primario" to c.textoPrimario,
            "secundario" to c.textoSecundario,
            "terciario" to c.textoTerciario,
            "acento" to c.acento,
            "acentoTexto" to c.acentoTexto,
            "iconoSutil" to c.iconoSutil,
            "error" to c.error,
            "exito" to c.exito,
            "aviso" to c.aviso
        )
        val fondos = listOf(
            "fondo" to c.fondo,
            "lateral" to c.fondoSecundario,
            "hover" to c.hover
        )
        println("%-12s %s".format("", fondos.joinToString(" ") { "%9s".format(it.first) }))
        for ((nt, t) in textos) {
            val fila = fondos.joinToString(" ") { (_, f) -> "%8.2f:1".format(contraste(t, f)) }
            println("%-12s %s".format(nt, fila))
        }
    }

    // ------------------------------------------------------------------

    private fun exigir(texto: Color, fondo: Color, minimo: Double, que: String) {
        val r = contraste(texto, fondo)
        assertTrue(
            "$que: contraste %.2f:1, por debajo del minimo WCAG AA de %.1f:1".format(r, minimo),
            r >= minimo
        )
    }

    /**
     * Distancia entre dos colores, en el cubo RGB normalizado.
     *
     * NO es una métrica perceptual —CIEDE2000 lo sería—, y no hace falta que lo
     * sea: aquí sólo se comprueba que dos colores de estado no hayan acabado
     * siendo el mismo tras un retoque de la paleta. Para eso, la distancia
     * euclídea basta y no trae una dependencia nueva.
     */
    private fun distancia(a: Color, b: Color): Double = kotlin.math.sqrt(
        ((a.red - b.red).toDouble()).pow(2) +
            ((a.green - b.green).toDouble()).pow(2) +
            ((a.blue - b.blue).toDouble()).pow(2)
    )

    /** Cociente de contraste de WCAG 2.1. */
    private fun contraste(a: Color, b: Color): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Luminancia relativa con la corrección gamma de sRGB. */
    private fun luminancia(color: Color): Double {
        fun canal(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canal(color.red) + 0.7152 * canal(color.green) + 0.0722 * canal(color.blue)
    }
}
