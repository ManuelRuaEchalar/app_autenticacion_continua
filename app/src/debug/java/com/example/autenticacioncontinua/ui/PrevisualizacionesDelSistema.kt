package com.example.autenticacioncontinua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.autenticacioncontinua.ui.componentes.AreaPrincipal
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.DestinoNavegacion
import com.example.autenticacioncontinua.ui.componentes.EstructuraConLateral
import com.example.autenticacioncontinua.ui.componentes.FilaDeLista
import com.example.autenticacioncontinua.ui.componentes.Separador
import com.example.autenticacioncontinua.ui.componentes.Tarjeta
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.theme.AutenticacionContinuaTheme
import com.example.autenticacioncontinua.ui.theme.EstiloCifra
import com.example.autenticacioncontinua.ui.theme.EstiloTeleprompter
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Muestrario del sistema de diseño.
 *
 * ESTÁ EN `src/debug` A PROPÓSITO: no se compila en la versión que se instala
 * en los terminales del estudio, así que no añade ni un byte ni un ciclo de CPU
 * al APK cuyo consumo se está midiendo.
 *
 * Sirve para dos cosas: ver de un vistazo si la paleta y los componentes
 * funcionan juntos, y comprobar el punto del plan «la interfaz se ve correcta
 * en teléfono y tablet» sin necesidad de un aparato — las dos anotaciones de
 * abajo renderizan los dos anchos.
 */

@Preview(name = "Componentes · teléfono", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
private fun VistaComponentesTelefono() = MuestrarioDeComponentes()

@Preview(name = "Componentes · tablet", widthDp = 900, heightDp = 700, showBackground = true)
@Composable
private fun VistaComponentesTablet() = MuestrarioDeComponentes()

@Composable
private fun MuestrarioDeComponentes() = AutenticacionContinuaTheme {
    AreaPrincipal {
        TituloDeSeccion("Acciones")
        Row {
            BotonPrimario("Empezar sesión", onClick = {})
            Spacer(Modifier.width(Tema.espaciado.pequeno))
            BotonSecundario("Cancelar", onClick = {})
        }
        Spacer(Modifier.height(Tema.espaciado.pequeno))
        Row {
            BotonPrimario("Deshabilitado", onClick = {}, habilitado = false)
        }

        TituloDeSeccion("Participantes")
        Tarjeta {
            Column {
                FilaDeLista("P01", apoyo = "última sesión: 22/08", valor = "4 sesiones",
                    seleccionada = true, onClick = {})
                Separador()
                FilaDeLista("P02", apoyo = "última sesión: 21/08", valor = "3 sesiones",
                    onClick = {})
                Separador()
                FilaDeLista("P03", apoyo = "sin sesiones", valor = "0 sesiones", onClick = {})
            }
        }

        TituloDeSeccion("Jerarquía de texto")
        Text("Texto primario", fontSize = Tipos.cuerpo, color = Tema.colores.textoPrimario)
        Text("Texto secundario", fontSize = Tipos.cuerpo, color = Tema.colores.textoSecundario)
        Text("Texto terciario", fontSize = Tipos.menor, color = Tema.colores.textoTerciario)

        TituloDeSeccion("Minijuego")
        Row {
            Column {
                Text("TIEMPO", fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
                Text("4:12", style = EstiloCifra, color = Tema.colores.textoPrimario)
            }
            Spacer(Modifier.width(Tema.espaciado.enorme))
            Column {
                Text("PRECISIÓN", fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
                Text("97%", style = EstiloCifra, color = Tema.colores.textoPrimario)
            }
            Spacer(Modifier.width(Tema.espaciado.enorme))
            Column {
                Text("PPM", fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
                Text("38", style = EstiloCifra, color = Tema.colores.textoPrimario)
            }
        }
        Spacer(Modifier.height(Tema.espaciado.medio))
        Teleprompter()
        Spacer(Modifier.height(Tema.espaciado.seccion))
    }
}

/**
 * Cómo se ve el texto a transcribir: lo ya escrito en gris, el error en rojo,
 * el carácter actual marcado con el acento legible, y lo que falta en primario.
 */
@Composable
private fun Teleprompter() {
    Row {
        Text("El veloz murci", style = EstiloTeleprompter, color = Tema.colores.textoTerciario)
        Text("é", style = EstiloTeleprompter, color = Tema.colores.error,
            fontWeight = FontWeight.Medium)
        Text(
            "l",
            style = EstiloTeleprompter,
            color = Tema.colores.acentoTexto,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Tema.colores.hover)
        )
        Text("ago hindú", style = EstiloTeleprompter, color = Tema.colores.textoPrimario)
    }
}

@Preview(name = "Navegación · teléfono (cajón)", widthDp = 400, heightDp = 800)
@Composable
private fun VistaNavegacionTelefono() = MuestrarioDeNavegacion()

@Preview(name = "Navegación · tablet (barra fija)", widthDp = 900, heightDp = 700)
@Composable
private fun VistaNavegacionTablet() = MuestrarioDeNavegacion()

@Composable
private fun MuestrarioDeNavegacion() = AutenticacionContinuaTheme {
    EstructuraConLateral(
        destinos = listOf(
            DestinoNavegacion("recoleccion", "Recolección"),
            DestinoNavegacion("controlada", "Sesión controlada"),
            DestinoNavegacion("participantes", "Participantes"),
            DestinoNavegacion("federado", "Aprendizaje federado")
        ),
        destinoActual = "controlada",
        onDestino = {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Tema.colores.fondo)
                .padding(Tema.espaciado.grande)
        ) {
            TituloDeSeccion("Sesión controlada")
            Text(
                "En pantalla estrecha la barra es un cajón; a partir de 600 dp " +
                    "se queda fija a la izquierda.",
                fontSize = Tipos.cuerpo,
                color = Tema.colores.textoSecundario
            )
        }
    }
}
