package com.example.autenticacioncontinua.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Tema de la aplicación.
 *
 * **NO USA COLOR DINÁMICO**, y es la decisión más importante de este fichero.
 * Material You deriva la paleta del fondo de pantalla del teléfono: dos
 * participantes verían literalmente colores distintos, y el mismo participante
 * vería otros al cambiar de fondo entre sesiones. Para una aplicación normal es
 * una gracia; para un instrumento de medida es una variable sin controlar que
 * afecta a la legibilidad de la tarea y —en panel OLED— al consumo de pantalla,
 * que es variable dependiente del estudio.
 *
 * **TAMPOCO SIGUE EL MODO OSCURO DEL SISTEMA**, por lo mismo: el modo se fija
 * por protocolo, igual que el brillo. Ver la nota de [ColoresClaros].
 *
 * El `MaterialTheme` se conserva debajo para que los componentes de Material 3
 * —campos de texto, diálogos— hereden la paleta en vez de traer la suya. Los
 * componentes propios leen de [Tema], que es la fuente de verdad.
 */
@Composable
fun AutenticacionContinuaTheme(
    content: @Composable () -> Unit
) {
    val colores = ColoresClaros

    val esquemaMaterial = lightColorScheme(
        primary = colores.botonPrimario,
        onPrimary = colores.textoSobreBotonPrimario,
        secondary = colores.acento,
        onSecondary = colores.textoSobreBotonPrimario,
        background = colores.fondo,
        onBackground = colores.textoPrimario,
        surface = colores.superficie,
        onSurface = colores.textoPrimario,
        surfaceVariant = colores.fondoSecundario,
        onSurfaceVariant = colores.textoSecundario,
        outline = colores.borde,
        error = colores.error,
        onError = colores.textoSobreBotonPrimario
    )

    CompositionLocalProvider(
        LocalColores provides colores,
        LocalEspaciado provides EspaciadoApp(),
        LocalFormas provides FormasApp()
    ) {
        MaterialTheme(
            colorScheme = esquemaMaterial,
            typography = Typography,
            content = content
        )
    }
}
