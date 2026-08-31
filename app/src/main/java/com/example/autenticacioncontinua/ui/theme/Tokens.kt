package com.example.autenticacioncontinua.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La ÚNICA definición de color de la aplicación.
 *
 * Ningún literal de color fuera de este fichero. No es una preferencia de
 * estilo: es lo que permite comprobar de una vez que todas las combinaciones
 * texto/fondo cumplen el contraste mínimo, y cambiar la paleta entera sin
 * perseguir `Color(0xFF...)` por veinte pantallas.
 *
 * Los nombres son SEMÁNTICOS —qué papel cumple el color— y no descriptivos. Un
 * token llamado `gris60` obliga a saber dónde se usa antes de tocarlo; uno
 * llamado `textoSecundario` dice qué se rompe si cambia.
 */
@Immutable
data class ColoresApp(
    val fondo: Color,
    val fondoSecundario: Color,
    val superficie: Color,
    val hover: Color,
    val borde: Color,
    val textoPrimario: Color,
    val textoSecundario: Color,
    /**
     * Metadatos: fechas, contadores, texto de ayuda.
     *
     * Es el gris más claro que aún se puede LEER: 4.5:1 sobre los fondos
     * claros. Ver [iconoSutil] para lo que no es texto.
     */
    val textoTerciario: Color,
    /**
     * Iconos decorativos, separadores marcados, marcadores de posición.
     *
     * NO SE USA PARA TEXTO NORMAL. Su contraste es 3.28:1, suficiente para un
     * elemento no textual y por debajo del mínimo legible.
     */
    val iconoSutil: Color,
    val botonPrimario: Color,
    val botonPrimarioHover: Color,
    val textoSobreBotonPrimario: Color,
    /**
     * Acento de la identidad visual. NO TEXTUAL: indicadores, barras de
     * progreso, fondo del elemento seleccionado, borde de foco.
     *
     * Con 3.20:1 sobre blanco cumple el mínimo de elementos no textuales y NO
     * el de texto. Para acento que haya que leer, [acentoTexto].
     */
    val acento: Color,
    /**
     * El mismo acento, oscurecido hasta ser legible.
     *
     * Mismo tono (165°) y misma saturación; sólo baja la luminosidad. Existe
     * porque el acento de la paleta se queda en 3.20:1 sobre blanco y el plan
     * lo usa también como texto —el carácter marcado del teleprompter, el
     * elemento seleccionado—, donde hacen falta 4.5:1.
     */
    val acentoTexto: Color,
    /** Sólo para el carácter fallado del teleprompter y los estados de error. */
    val error: Color,

    /**
     * Fondo del teclado del minijuego.
     *
     * Más oscuro que las teclas para que éstas se lean como teclas sin
     * necesidad de bordes ni sombras, que es como lo resuelven los teclados de
     * Android. Ver [teclaFuncion].
     */
    val teclaFondo: Color,

    /**
     * Fondo de las teclas que no escriben un carácter: hoy sólo el retroceso.
     *
     * Los teclados del sistema las tiñen para distinguirlas de las letras de un
     * vistazo, y el teclado del estudio lo copia: cuanto más se parezca al que
     * el participante usa a diario, menos mide el estudio la sorpresa de un
     * teclado desconocido.
     */
    val teclaFuncion: Color
)

/**
 * Paleta del estudio.
 *
 * MODO CLARO FIJO, Y NO ES UNA DECISIÓN ESTÉTICA. Dos razones, y la primera
 * afecta directamente a una variable dependiente:
 *
 * 1. **El consumo de pantalla depende del color mostrado.** En un panel OLED
 *    los píxeles oscuros consumen menos, así que un participante en modo oscuro
 *    y otro en modo claro darían cifras de batería no comparables — y la
 *    batería es la variable dependiente principal de las propuestas I y II. El
 *    modo se fija por protocolo, igual que el brillo.
 * 2. **La tarea tiene que ser idéntica entre participantes.** El teleprompter
 *    con el carácter actual marcado se lee distinto según el contraste.
 */
val ColoresClaros = ColoresApp(
    fondo = Color(0xFFFFFFFF),
    fondoSecundario = Color(0xFFF7F7F8),
    superficie = Color(0xFFFFFFFF),
    hover = Color(0xFFECECEC),
    borde = Color(0xFFE5E5E5),
    textoPrimario = Color(0xFF0D0D0D),
    textoSecundario = Color(0xFF676767),
    // Sube desde el #8E8E8E del plan, que daba 3.28:1 sobre blanco y no era
    // legible según WCAG AA. El valor original se conserva en `iconoSutil`,
    // que es donde 3:1 sí basta. Lo detectó `ContrasteTest`.
    textoTerciario = Color(0xFF767676),
    iconoSutil = Color(0xFF8E8E8E),
    botonPrimario = Color(0xFF2F2F2F),
    botonPrimarioHover = Color(0xFF424242),
    textoSobreBotonPrimario = Color(0xFFFFFFFF),
    acento = Color(0xFF10A37F),
    acentoTexto = Color(0xFF0C795E),
    // Rojo oscurecido respecto al habitual (#D93025) para llegar a 4.5:1 sobre
    // blanco: el carácter fallado del teleprompter es texto que hay que leer,
    // no un adorno.
    error = Color(0xFFC5221F),
    // Grises del teclado, tomados de la disposición clara por defecto de los
    // teclados de Android. NO se copia el tema del teléfono que se usó para
    // medirlo —ese tenía un tema personalizado color melocotón—: el aspecto del
    // teclado tiene que ser IDÉNTICO en los dos terminales del estudio, y un
    // tema elegido por el dueño del móvil es justo una variable sin controlar.
    teclaFondo = Color(0xFFE3E3E5),
    teclaFuncion = Color(0xFFD0D0D3)
)

/**
 * Espaciado en una escala de 4.
 *
 * Una escala cerrada evita el "16 aquí, 18 allá, 15 más allá" que hace que una
 * interfaz parezca descuidada sin que se sepa señalar por qué.
 */
@Immutable
data class EspaciadoApp(
    val minimo: Dp = 4.dp,
    val pequeno: Dp = 8.dp,
    val medio: Dp = 16.dp,
    val grande: Dp = 24.dp,
    val enorme: Dp = 32.dp,
    val seccion: Dp = 48.dp
)

/** Radios y grosores. Esquinas moderadas, bordes finos, sin sombras. */
@Immutable
data class FormasApp(
    val radioPequeno: Dp = 6.dp,
    val radioMedio: Dp = 10.dp,
    val radioGrande: Dp = 16.dp,
    val grosorBorde: Dp = 1.dp,
    /** Ancho máximo de lectura del área principal. */
    val anchoLectura: Dp = 720.dp,
    /**
     * Lado mínimo de un objetivo táctil.
     *
     * 48 dp es la recomendación de accesibilidad de Android, y aquí además es
     * una condición del experimento: teclas más pequeñas producirían errores de
     * puntería que se confundirían con errores de tecleo del participante.
     */
    val objetivoTactil: Dp = 48.dp
)

val LocalColores = staticCompositionLocalOf { ColoresClaros }
val LocalEspaciado = staticCompositionLocalOf { EspaciadoApp() }
val LocalFormas = staticCompositionLocalOf { FormasApp() }

/** Acceso corto a los tokens desde cualquier `@Composable`: `Tema.colores.fondo`. */
object Tema {
    val colores: ColoresApp
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalColores.current

    val espaciado: EspaciadoApp
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalEspaciado.current

    val formas: FormasApp
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalFormas.current
}

/**
 * Tamaños de letra. Pocos, y con un papel cada uno.
 *
 * `texto` es el del teleprompter: 20 sp, más grande que el cuerpo normal,
 * porque el participante lo lee mientras teclea sin mirar la pantalla del todo.
 */
object Tipos {
    val titulo = 24.sp
    val subtitulo = 18.sp
    val cuerpo = 15.sp
    val menor = 13.sp
    val texto = 20.sp
    val cifra = 28.sp
}
