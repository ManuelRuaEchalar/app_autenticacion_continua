package com.example.autenticacioncontinua.ui.tecleo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Teclado propio del estudio, calcado del teclado español de MIUI.
 *
 * ### Por qué el teclado es propio y no el del sistema
 *
 * Por una obligación técnica y por una razón metodológica.
 *
 * **Técnica:** los teclados software de Android no envían `KeyEvent` por tecla,
 * entregan texto ya compuesto por `InputConnection.commitText`. Un `TextWatcher`
 * ve aparecer caracteres, no dedos. Con el teclado del sistema no existe el
 * tiempo de permanencia —`up − down` de una misma tecla, la magnitud de la
 * dinámica de tecleo que menos depende del texto copiado— ni la presión ni el
 * área, porque el `MotionEvent` se lo queda el proceso del teclado.
 *
 * **Metodológica:** cada terminal trae su teclado, con su tamaño y su separación
 * de teclas. La geometría de la pulsación sería entonces otra variable de
 * dispositivo confundida con la persona, que es justo lo que el diseño cruzado
 * existe para evitar. Con teclado propio la disposición es idéntica en los dos
 * aparatos y entre todos los participantes.
 *
 * ### Por qué, aun así, se parece al de MIUI (decisión del 30/08)
 *
 * El coste declarado de tener teclado propio era que **teclear en un teclado
 * desconocido no es teclear en el propio**: parte de lo que se mediría sería la
 * sorpresa de una disposición nueva, y esa sorpresa se disipa con la práctica,
 * de modo que quedaría confundida con el número de sesión.
 *
 * Ese coste se reduce copiando la disposición del teclado que los participantes
 * ya usan. Los dos terminales del estudio son Redmi, así que la referencia es el
 * teclado español de MIUI, medido sobre el propio aparato:
 *
 * - tres filas alfabéticas de diez columnas, con `ñ` cerrando la fila central;
 * - la fila de abajo con una tecla de función ancha a cada lado —donde MIUI pone
 *   Mayúsculas y Retroceso— y siete letras en medio, de `z` a `m`;
 * - coma y punto flanqueando la barra espaciadora, no metidas entre las letras;
 * - teclas blancas sobre fondo gris, esquinas redondeadas, y las de función en
 *   un gris más marcado.
 *
 * **Lo que NO se copia** y hay que declararlo:
 *
 * - **el tema del teléfono.** El terminal con el que se midió tenía un tema
 *   color melocotón; el teclado del estudio usa los grises neutros por defecto.
 *   Un tema elegido por el dueño del móvil sería una variable sin controlar y
 *   distinta en cada aparato.
 * - **las mayúsculas.** El corpus está en minúsculas a propósito: pulsar Mayús
 *   es un gesto distinto —dos dedos, o un desplazamiento de la mano— que se
 *   mezclaría con la dinámica que se quiere medir. El hueco de la tecla de
 *   Mayúsculas se conserva vacío para no desplazar las letras respecto a MIUI.
 * - **los dígitos, el emoji y el intro.** No aparecen en ningún texto del
 *   corpus. Una tecla que no hace nada invita a probarla, y esa pulsación no
 *   pertenece a la tarea.
 * - **las teclas muertas.** En MIUI los acentos salen manteniendo pulsada la
 *   vocal. Aquí van en su propia fila arriba, porque una tecla muerta
 *   convertiría `á` en dos pulsaciones para un solo carácter esperado y rompería
 *   la correspondencia una tecla → un carácter de la que vive
 *   [com.example.autenticacioncontinua.data.tecleo.RegistroDeTecleo].
 *
 * ### La disposición está congelada
 *
 * Cambiarla a mitad del estudio cambia la conducta motora que se está midiendo y
 * parte el corpus en dos.
 */
@Composable
fun TecladoEnPantalla(
    onPulsacion: (PulsacionCruda) -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Tema.colores.teclaFondo)
            .padding(
                horizontal = Tema.espaciado.minimo,
                vertical = Tema.espaciado.pequeno
            ),
        verticalArrangement = Arrangement.spacedBy(Tema.espaciado.pequeno)
    ) {
        // Fila de acentos. Va ARRIBA y no repartida entre las letras para que
        // las tres filas alfabéticas queden exactamente donde MIUI las pone.
        FilaCentrada(ACENTOS, onPulsacion, habilitado)

        FilaCompleta(FILA_SUPERIOR, onPulsacion, habilitado)
        FilaCompleta(FILA_CENTRAL, onPulsacion, habilitado)

        // La fila de MIUI: Mayúsculas | z…m | Retroceso. El hueco de Mayúsculas
        // se deja vacío para no correr las letras media tecla a la izquierda.
        Fila {
            Box(Modifier.weight(FUNCION))
            for (t in FILA_INFERIOR) Tecla(t, t, Modifier.weight(1f), onPulsacion, habilitado)
            Tecla(
                caracter = "",
                rotulo = "⌫",
                modifier = Modifier.weight(FUNCION),
                onPulsacion = onPulsacion,
                habilitado = habilitado,
                esRetroceso = true,
                esFuncion = true
            )
        }

        // Coma y punto flanqueando el espacio, como en MIUI.
        Fila {
            Tecla(",", ",", Modifier.weight(FUNCION), onPulsacion, habilitado)
            Tecla(ESPACIO, "", Modifier.weight(ESPACIO_ANCHO), onPulsacion, habilitado)
            Tecla(".", ".", Modifier.weight(FUNCION), onPulsacion, habilitado)
        }
    }
}

@Composable
private fun Fila(contenido: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tema.espaciado.minimo),
        content = contenido
    )
}

/** Diez teclas de igual ancho, ocupando la fila entera. */
@Composable
private fun FilaCompleta(
    teclas: List<String>,
    onPulsacion: (PulsacionCruda) -> Unit,
    habilitado: Boolean
) = Fila {
    for (t in teclas) Tecla(t, t, Modifier.weight(1f), onPulsacion, habilitado)
}

/**
 * Fila más corta, centrada repartiendo el hueco sobrante a los lados.
 *
 * No se estiran las teclas para rellenar: una tecla más ancha en la fila de los
 * acentos tendría otra geometría que las demás, y la posición del dedo DENTRO de
 * la tecla es uno de los canales que se registran.
 */
@Composable
private fun FilaCentrada(
    teclas: List<String>,
    onPulsacion: (PulsacionCruda) -> Unit,
    habilitado: Boolean
) = Fila {
    val hueco = (COLUMNAS - teclas.size) / 2f
    Box(Modifier.weight(hueco))
    for (t in teclas) Tecla(t, t, Modifier.weight(1f), onPulsacion, habilitado)
    Box(Modifier.weight(hueco))
}

/**
 * Una tecla.
 *
 * SE ILUMINA AL PULSARLA, como la del sistema. No es adorno: sin respuesta
 * visual el participante duda de si la pulsación entró y repite la tecla, y esa
 * repetición no es dinámica de tecleo sino incertidumbre sobre la interfaz. El
 * realce se pinta desde el mismo evento que se registra, así que no añade
 * ninguna latencia a la medición.
 *
 * El área pulsable nunca baja de
 * [com.example.autenticacioncontinua.ui.theme.FormasApp.objetivoTactil]: teclas
 * más pequeñas producen errores de puntería que en el análisis se confundirían
 * con errores de tecleo del participante. Son un par de dp más altas que las de
 * MIUI, y esa diferencia se prefiere al riesgo contrario.
 */
@Composable
private fun Tecla(
    caracter: String,
    rotulo: String,
    modifier: Modifier,
    onPulsacion: (PulsacionCruda) -> Unit,
    habilitado: Boolean,
    esRetroceso: Boolean = false,
    esFuncion: Boolean = false
) {
    var pulsada by remember { mutableStateOf(false) }

    val fondo = when {
        !habilitado -> Tema.colores.teclaFuncion
        pulsada -> Tema.colores.hover
        esFuncion -> Tema.colores.teclaFuncion
        else -> Tema.colores.superficie
    }

    Box(
        modifier
            .height(Tema.formas.objetivoTactil)
            .background(fondo, RoundedCornerShape(RADIO_TECLA))
            .then(
                if (habilitado) {
                    Modifier.capturaDePulsacion(
                        caracter = caracter,
                        esRetroceso = esRetroceso
                    ) { p ->
                        pulsada = p.fase == FaseDePulsacion.ABAJO
                        onPulsacion(p)
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (rotulo.isNotEmpty()) {
            Text(
                rotulo,
                fontSize = Tipos.subtitulo,
                fontWeight = FontWeight.Normal,
                color = if (habilitado) Tema.colores.textoPrimario
                else Tema.colores.textoTerciario
            )
        }
    }
}

/** Diez columnas: es lo que fija el ancho de todas las letras por igual. */
private const val COLUMNAS = 10

/** Ancho de las teclas de función, en columnas. En MIUI son vez y media. */
private const val FUNCION = 1.5f

/** La barra espaciadora ocupa lo que dejan la coma y el punto. */
private const val ESPACIO_ANCHO = COLUMNAS - 2 * FUNCION

/** MIUI redondea bastante las teclas; 8 dp es lo medido. */
private val RADIO_TECLA = 8.dp

private const val ESPACIO = " "

private val ACENTOS = listOf("á", "é", "í", "ó", "ú", "ü")
private val FILA_SUPERIOR = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val FILA_CENTRAL = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ")

/** De `z` a `m`: las siete que MIUI deja entre Mayúsculas y Retroceso. */
private val FILA_INFERIOR = listOf("z", "x", "c", "v", "b", "n", "m")
