package com.example.autenticacioncontinua.ui.componentes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos
import kotlinx.coroutines.launch

/** Una entrada de la barra lateral. */
data class DestinoNavegacion(
    val clave: String,
    val etiqueta: String
)

/**
 * Ancho a partir del cual la barra lateral se queda fija.
 *
 * 600 dp es el corte habitual entre teléfono y tablet en Android. Por debajo,
 * una barra permanente se comería el ancho que el teleprompter necesita.
 */
private val ANCHO_TABLET = 600.dp
private val ANCHO_LATERAL = 260.dp

/**
 * Estructura de navegación: cajón en teléfono, barra fija en tablet.
 *
 * POR QUÉ SE DECIDE POR ANCHO Y NO POR «ES UNA TABLET». Lo que importa es el
 * espacio disponible, no la categoría del aparato: un teléfono en horizontal
 * tiene el ancho de una tablet pequeña, y una tablet en multiventana puede
 * tener el de un teléfono. Preguntar por el ancho actual acierta en los dos
 * casos y no necesita saber qué aparato es.
 *
 * LA BARRA ES VISUALMENTE SECUNDARIA, como pide el plan: fondo gris muy claro,
 * sin bordes propios más allá del separador vertical, y el elemento
 * seleccionado marcado con un fondo suave en vez de con un bloque de color.
 */
@Composable
fun EstructuraConLateral(
    destinos: List<DestinoNavegacion>,
    destinoActual: String,
    onDestino: (String) -> Unit,
    contenido: @Composable () -> Unit
) {
    val esAncha = LocalConfiguration.current.screenWidthDp.dp >= ANCHO_TABLET

    if (esAncha) {
        Row(Modifier.fillMaxSize().background(Tema.colores.fondo)) {
            BarraLateral(destinos, destinoActual, onDestino, Modifier.width(ANCHO_LATERAL))
            SeparadorVertical()
            Box(Modifier.weight(1f)) { contenido() }
        }
    } else {
        val estado = rememberDrawerState(DrawerValue.Closed)
        val alcance = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = estado,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Tema.colores.fondoSecundario,
                    drawerContentColor = Tema.colores.textoPrimario
                ) {
                    BarraLateral(destinos, destinoActual, onDestino = { clave ->
                        onDestino(clave)
                        // Se cierra al elegir: dejarlo abierto tapa el contenido
                        // al que se acaba de navegar.
                        alcance.launch { estado.close() }
                    })
                }
            }
        ) {
            Box(Modifier.fillMaxSize().background(Tema.colores.fondo)) { contenido() }
        }
    }
}

@Composable
private fun BarraLateral(
    destinos: List<DestinoNavegacion>,
    destinoActual: String,
    onDestino: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(Tema.colores.fondoSecundario)
            .padding(Tema.espaciado.pequeno)
    ) {
        for (d in destinos) {
            EntradaLateral(
                etiqueta = d.etiqueta,
                seleccionada = d.clave == destinoActual,
                onClick = { onDestino(d.clave) }
            )
        }
    }
}

@Composable
private fun EntradaLateral(
    etiqueta: String,
    seleccionada: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Tema.espaciado.minimo)
            .background(
                // El seleccionado se marca con fondo suave y peso de letra, no
                // con color de acento: el acento sobre el fondo de la barra
                // lateral se queda en 2.99:1 y no distinguiria lo suficiente.
                // Lo midió `ContrasteTest`.
                if (seleccionada) Tema.colores.hover else Tema.colores.fondoSecundario,
                RoundedCornerShape(Tema.formas.radioPequeno)
            )
            .clickable(onClick = onClick)
            .heightIn(min = Tema.formas.objetivoTactil)
            .padding(horizontal = Tema.espaciado.medio),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            etiqueta,
            fontSize = Tipos.cuerpo,
            fontWeight = if (seleccionada) FontWeight.Medium else FontWeight.Normal,
            color = if (seleccionada) Tema.colores.textoPrimario
            else Tema.colores.textoSecundario
        )
    }
}

@Composable
private fun SeparadorVertical() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(Tema.formas.grosorBorde)
            .background(Tema.colores.borde)
    )
}
