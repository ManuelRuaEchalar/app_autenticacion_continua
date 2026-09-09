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
 * Pocos estilos y pocos pesos. Sólo tres —normal, medio, semi— porque una
 * herramienta de trabajo no necesita más y cada peso extra es otro fichero que
 * empaquetar.
 *
 * ### SE RELLENAN LOS QUINCE HUECOS DE MATERIAL, Y NO ES POR COMPLETISMO
 *
 * `Typography` define quince estilos. Esta tabla llenaba cinco, y los diez
 * restantes se quedaban con los de fábrica de Material 3 — que traen
 * `FontFamily.Default`, no [FamiliaApp]. Cualquier `MaterialTheme.typography.
 * bodySmall` o `headlineSmall` salía entonces con la fuente del sistema, y los
 * componentes de Material que usan esos huecos por dentro —las etiquetas de
 * `OutlinedTextField`, el título de `AlertDialog`— también.
 *
 * O sea que la aplicación mezclaba dos tipografías sin que nadie lo hubiera
 * pedido, y en dos terminales de fabricantes distintos ni siquiera mezclaba las
 * mismas dos. Con la fuente empaquetada pendiente (ver arriba), rellenarlos es
 * lo que hace que ese cambio de una constante alcance de verdad a TODA la
 * interfaz en vez de a un tercio.
 *
 * Los tamaños salen de [Tipos], que es la escala del sistema de diseño; los
 * huecos que la escala no cubre se interpolan y se dejan anotados.
 */
private fun estilo(
    tamano: androidx.compose.ui.unit.TextUnit,
    alto: androidx.compose.ui.unit.TextUnit,
    peso: FontWeight = FontWeight.Normal
) = TextStyle(
    fontFamily = FamiliaApp,
    fontWeight = peso,
    fontSize = tamano,
    lineHeight = alto
)

val Typography = Typography(
    // `display*` no se usa en ninguna pantalla; se define igualmente para que
    // un componente de Material que lo pida no se lleve la fuente del sistema.
    displayLarge = estilo(40.sp, 48.sp, FontWeight.SemiBold),
    displayMedium = estilo(34.sp, 42.sp, FontWeight.SemiBold),
    displaySmall = estilo(Tipos.cifra, 36.sp, FontWeight.SemiBold),

    headlineLarge = estilo(30.sp, 38.sp, FontWeight.SemiBold),
    headlineMedium = estilo(Tipos.cifra, 36.sp, FontWeight.SemiBold),
    headlineSmall = estilo(Tipos.titulo, 32.sp, FontWeight.SemiBold),

    titleLarge = estilo(Tipos.titulo, 32.sp, FontWeight.SemiBold),
    titleMedium = estilo(Tipos.subtitulo, 26.sp, FontWeight.Medium),
    titleSmall = estilo(Tipos.cuerpo, 22.sp, FontWeight.Medium),

    bodyLarge = estilo(Tipos.cuerpo, 22.sp),
    bodyMedium = estilo(Tipos.menor, 18.sp),
    // El escalón por debajo del menor de la escala. Es el mínimo que se admite
    // como texto seguido: menos que esto ya no se lee de reojo, que es como se
    // mira esta aplicación durante una sesión.
    bodySmall = estilo(12.sp, 17.sp),

    labelLarge = estilo(Tipos.cuerpo, 20.sp, FontWeight.Medium),
    labelMedium = estilo(Tipos.menor, 17.sp, FontWeight.Medium),
    labelSmall = estilo(11.sp, 15.sp, FontWeight.Medium)
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
