package com.example.autenticacioncontinua.ui.componentes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Los estados que la interfaz sabe decir, y el único vocabulario con el que una
 * pantalla puede pedir un color.
 *
 * ### Por qué un enumerado en vez de pasar el color
 *
 * `Base.kt` fija la regla: ningún componente acepta un `Color` como parámetro,
 * porque en cuanto lo acepta la paleta deja de estar en un sitio y
 * `ContrasteTest` deja de poder comprobar qué combinaciones existen de verdad.
 * Pero la herramienta de recolección SÍ necesita hablar de estados —el servicio
 * está grabando, falta la exención de batería, este resultado no es de fiar— y
 * antes lo hacía pasando `AccentGreen` o `AccentRed` de mano en mano.
 *
 * La salida es que las pantallas nombren el ESTADO y no el color. Aquí, en un
 * único sitio, se traduce a paleta. Cambiar qué verde significa «va bien» pasa a
 * ser una línea, y sigue habiendo una lista cerrada de combinaciones que
 * `ContrasteTest` puede recorrer entera.
 */
enum class EstadoVisual {
    /** Ni bueno ni malo: en espera, en pausa, sin datos todavía. */
    NEUTRO,

    /** Identidad visual, no juicio: progreso, elemento marcado, dato destacado. */
    ACENTO,

    /** Confirmado y correcto: grabando, protegido, guardado. */
    EXITO,

    /**
     * Funciona, pero no es lo que parece.
     *
     * Es el estado más útil de los cuatro y el primero que se pierde cuando
     * sólo hay «bien» y «mal»: una federación que termina con fuga de datos, o
     * una recolección suspendida a propósito, no son fallos y tampoco son un
     * visto bueno.
     */
    AVISO,

    /** Roto: hay que pararse y arreglarlo antes de seguir midiendo. */
    ERROR
}

/**
 * El color de un estado.
 *
 * `internal` a propósito: es la frontera del sistema de diseño. Fuera de
 * `ui/componentes` se usan [PuntoDeEstado], [TextoDeEstado], [CabeceraDeEstado]
 * y [TarjetaDeAviso], que no exponen ningún color.
 */
@Composable
@ReadOnlyComposable
internal fun colorDe(estado: EstadoVisual): Color = when (estado) {
    EstadoVisual.NEUTRO -> Tema.colores.textoSecundario
    EstadoVisual.ACENTO -> Tema.colores.acentoTexto
    EstadoVisual.EXITO -> Tema.colores.exito
    EstadoVisual.AVISO -> Tema.colores.aviso
    EstadoVisual.ERROR -> Tema.colores.error
}

/**
 * Punto de estado. Late cuando algo está ocurriendo AHORA.
 *
 * EL LATIDO NO ES ADORNO. Distingue «se está grabando en este instante» de «la
 * última grabación salió bien», que son dos cosas que un punto quieto no separa.
 * En un estudio cuya recolección corre en segundo plano, esa distinción es la
 * única señal de que el servicio sigue vivo.
 *
 * EL COLOR NUNCA VA SOLO. Un punto de color no dice nada a quien no distingue
 * rojo de verde, así que en todas las pantallas va pegado al texto del estado,
 * que dice lo mismo con palabras.
 */
@Composable
fun PuntoDeEstado(
    estado: EstadoVisual,
    modifier: Modifier = Modifier,
    pulsante: Boolean = false
) {
    val color = colorDe(estado)
    val alfa = if (pulsante) {
        rememberInfiniteTransition(label = "latido").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                tween(800, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "latido_alfa"
        ).value
    } else {
        1f
    }

    Box(
        modifier
            .size(DIAMETRO_PUNTO)
            .background(color.copy(alpha = alfa), CircleShape)
    )
}

/** Texto teñido por su estado. El único camino para colorear una frase. */
@Composable
fun TextoDeEstado(
    texto: String,
    estado: EstadoVisual,
    modifier: Modifier = Modifier,
    tamano: TextUnit = Tipos.cuerpo,
    peso: FontWeight = FontWeight.Normal
) {
    Text(
        texto,
        modifier = modifier,
        color = colorDe(estado),
        fontSize = tamano,
        fontWeight = peso,
        lineHeight = tamano * 1.45f
    )
}

/**
 * Encabezado de una tarjeta de estado: punto, título teñido y detalle en gris.
 *
 * Es la forma que repetían la tarjeta de sesión, la de protección y el panel de
 * captura, cada una con su propio espaciado.
 */
@Composable
fun CabeceraDeEstado(
    titulo: String,
    estado: EstadoVisual,
    modifier: Modifier = Modifier,
    detalle: String? = null,
    pulsante: Boolean = false
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PuntoDeEstado(estado, pulsante = pulsante)
            TextoDeEstado(
                titulo,
                estado,
                modifier = Modifier.padding(start = Tema.espaciado.pequeno),
                tamano = Tipos.subtitulo,
                peso = FontWeight.SemiBold
            )
        }
        if (detalle != null) {
            Text(
                detalle,
                modifier = Modifier.padding(top = Tema.espaciado.pequeno),
                color = Tema.colores.textoSecundario,
                fontSize = Tipos.menor,
                lineHeight = Tipos.menor * 1.5f
            )
        }
    }
}

/**
 * Aviso destacado: una marca del color del estado a la izquierda y el texto
 * teñido a juego.
 *
 * EL FONDO NO SE TIÑE, y es el cambio que trae respecto a la versión anterior.
 * Aquélla pintaba el fondo con el color del aviso al 12 % de opacidad, y eso
 * rompe la comprobación de contraste: el texto se medía contra un fondo que ya
 * no era ninguno de los de la paleta, sino una mezcla que dependía de lo que
 * hubiera debajo. Aquí el fondo es el secundario de siempre —cuyo contraste sí
 * está comprobado— y el color va donde se ve sin alterar nada.
 */
@Composable
fun TarjetaDeAviso(
    texto: String,
    estado: EstadoVisual,
    modifier: Modifier = Modifier
) {
    val forma = RoundedCornerShape(Tema.formas.radioPequeno)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Tema.colores.fondoSecundario, forma)
            .border(Tema.formas.grosorBorde, Tema.colores.borde, forma)
    ) {
        Box(
            Modifier
                .width(GROSOR_MARCA)
                .fillMaxHeight()
                .background(colorDe(estado))
        )
        TextoDeEstado(
            texto,
            estado,
            modifier = Modifier.padding(Tema.espaciado.medio),
            tamano = Tipos.menor
        )
    }
}

private val DIAMETRO_PUNTO = 10.dp

/** Marca vertical del aviso: fina, para que sea acento y no un bloque de color. */
private val GROSOR_MARCA = 3.dp
