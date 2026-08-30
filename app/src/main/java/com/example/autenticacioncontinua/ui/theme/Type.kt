package com.example.autenticacioncontinua.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Familia tipográfica de la aplicación.
 *
 * **PENDIENTE ANTES DEL CAMPO: empaquetar Inter.** Ahora mismo se usa la
 * sans-serif del sistema, y eso es un problema del experimento, no de estética:
 * los fabricantes sustituyen la fuente del sistema —MIUI trae la suya—, de modo
 * que con dos terminales de fabricantes distintos **el texto del teleprompter
 * tendría métricas distintas en cada uno**. Cambiaría dónde caen los saltos de
 * línea y cuánto texto ve el participante de un vistazo, y eso es parte de la
 * tarea que el diseño quiere mantener idéntica entre dispositivos.
 *
 * El arreglo es un fichero: dejar `Inter.ttf` en `res/font` y sustituir esta
 * constante por `FontFamily(Font(R.font.inter))`. Todo lo demás ya está
 * centralizado aquí.
 */
val FamiliaApp = FontFamily.SansSerif

/**
 * Pocos estilos y pocos pesos. Sólo tres pesos —normal, medio, semi— porque una
 * herramienta de trabajo no necesita más y cada peso extra es otro fichero que
 * empaquetar.
 */
val Typography = Typography(
    titleLarge = TextStyle(
        fontFamily = FamiliaApp,
        fontWeight = FontWeight.SemiBold,
        fontSize = Tipos.titulo,
        lineHeight = 32.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FamiliaApp,
        fontWeight = FontWeight.Medium,
        fontSize = Tipos.subtitulo,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FamiliaApp,
        fontWeight = FontWeight.Normal,
        fontSize = Tipos.cuerpo,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FamiliaApp,
        fontWeight = FontWeight.Normal,
        fontSize = Tipos.menor,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FamiliaApp,
        fontWeight = FontWeight.Medium,
        fontSize = Tipos.cuerpo,
        lineHeight = 20.sp
    )
)

/**
 * Estilo del texto que se transcribe.
 *
 * Más grande que el cuerpo y con interlineado holgado: el participante lo lee
 * de reojo mientras teclea, no con la atención puesta en la pantalla.
 */
val EstiloTeleprompter = TextStyle(
    fontFamily = FamiliaApp,
    fontWeight = FontWeight.Normal,
    fontSize = Tipos.texto,
    lineHeight = 34.sp
)

/** Cifras de la barra de estado del minijuego: tiempo, precisión, ppm. */
val EstiloCifra = TextStyle(
    fontFamily = FamiliaApp,
    fontWeight = FontWeight.Medium,
    fontSize = Tipos.cifra,
    lineHeight = 34.sp
)
