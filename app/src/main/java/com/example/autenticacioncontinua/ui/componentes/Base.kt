package com.example.autenticacioncontinua.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Los ladrillos de la interfaz.
 *
 * NINGUNO ACEPTA UN COLOR COMO PARÁMETRO. Es deliberado: en cuanto un
 * componente admite `color = ...`, la paleta deja de estar en un sitio y
 * empieza a estar repartida por las pantallas, que es exactamente lo que la
 * prueba de contraste no podría volver a comprobar. Si hace falta una variante
 * nueva, se añade aquí y se justifica.
 */

/** Acción principal de una pantalla. Sólo debería haber una visible a la vez. */
@Composable
fun BotonPrimario(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.defaultMinSize(minHeight = Tema.formas.objetivoTactil),
        shape = RoundedCornerShape(Tema.formas.radioMedio),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tema.colores.botonPrimario,
            contentColor = Tema.colores.textoSobreBotonPrimario,
            // El deshabilitado se apoya en el gris de icono, no en una
            // transparencia: sobre fondos distintos, una transparencia da
            // contrastes distintos y deja de ser comprobable.
            disabledContainerColor = Tema.colores.hover,
            disabledContentColor = Tema.colores.textoTerciario
        )
    ) {
        Text(texto, fontSize = Tipos.cuerpo, fontWeight = FontWeight.Medium)
    }
}

/** Acción secundaria: mismo peso visual que el fondo, sólo delimitada. */
@Composable
fun BotonSecundario(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.defaultMinSize(minHeight = Tema.formas.objetivoTactil),
        shape = RoundedCornerShape(Tema.formas.radioMedio),
        border = BorderStroke(Tema.formas.grosorBorde, Tema.colores.borde),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Tema.colores.textoPrimario,
            disabledContentColor = Tema.colores.textoTerciario
        )
    ) {
        Text(texto, fontSize = Tipos.cuerpo)
    }
}

/**
 * Contenedor de contenido. Sin sombra: sólo un borde de un píxel.
 *
 * El plan pide «sombras mínimas o ninguna», y aquí es ninguna. Una sombra sobre
 * fondo blanco no aporta jerarquía y sí ruido visual en una pantalla que el
 * participante va a mirar durante quince minutos.
 */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    contenido: @Composable () -> Unit
) {
    val forma = RoundedCornerShape(Tema.formas.radioMedio)
    Box(
        modifier
            .background(Tema.colores.superficie, forma)
            // El borde estaba descrito arriba y no se dibujaba. Sobre el fondo
            // principal —blanco, igual que `superficie`— eso dejaba la tarjeta
            // invisible: se veía el contenido flotando y no el contenedor. Con
            // la migración de la herramienta de recolección, donde casi todo
            // vive dentro de tarjetas, el fallo pasó de cosmético a estructural.
            .border(Tema.formas.grosorBorde, Tema.colores.borde, forma)
            .padding(Tema.espaciado.medio)
    ) { contenido() }
}

/**
 * Fila de una lista: título, apoyo opcional y valor a la derecha.
 *
 * Es el componente de la lista de participantes y del resumen de sesión. El
 * área pulsable ocupa la fila entera y nunca baja del objetivo táctil mínimo:
 * una fila de 36 dp produce toques fallidos que en un estudio de tecleo se
 * confundirían con torpeza del participante.
 */
@Composable
fun FilaDeLista(
    titulo: String,
    modifier: Modifier = Modifier,
    apoyo: String? = null,
    valor: String? = null,
    seleccionada: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                if (seleccionada) Tema.colores.hover else Tema.colores.superficie,
                RoundedCornerShape(Tema.formas.radioPequeno)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Tema.formas.objetivoTactil)
            .padding(horizontal = Tema.espaciado.medio, vertical = Tema.espaciado.pequeno),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                fontSize = Tipos.cuerpo,
                fontWeight = if (seleccionada) FontWeight.Medium else FontWeight.Normal,
                color = Tema.colores.textoPrimario
            )
            if (apoyo != null) {
                Text(apoyo, fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
            }
        }
        if (valor != null) {
            Text(
                valor,
                fontSize = Tipos.menor,
                color = Tema.colores.textoSecundario,
                modifier = Modifier.padding(start = Tema.espaciado.pequeno)
            )
        }
    }
}

/** Separador. Un píxel del color más claro de la paleta; nunca una línea gruesa. */
@Composable
fun Separador(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = Tema.formas.grosorBorde,
        color = Tema.colores.borde
    )
}

/** Encabezado de sección: el único sitio con peso semi. */
@Composable
fun TituloDeSeccion(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto,
        modifier = modifier.padding(
            top = Tema.espaciado.grande,
            bottom = Tema.espaciado.pequeno
        ),
        fontSize = Tipos.subtitulo,
        fontWeight = FontWeight.SemiBold,
        color = Tema.colores.textoPrimario
    )
}

/**
 * Área principal: centrada y con ancho máximo de lectura.
 *
 * En una tablet, un párrafo que ocupe los 1200 px de ancho es incómodo de leer
 * — y en el minijuego, directamente cambia la tarea: el participante tendría
 * que barrer la cabeza para seguir el teleprompter. El ancho se acota aquí y no
 * en cada pantalla para que sea el mismo en todas.
 */
@Composable
fun AreaPrincipal(
    modifier: Modifier = Modifier,
    contenido: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Tema.colores.fondo),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier
                .widthIn(max = Tema.formas.anchoLectura)
                .padding(horizontal = Tema.espaciado.grande)
        ) { contenido() }
    }
}
