package com.example.autenticacioncontinua.ui

import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import com.example.autenticacioncontinua.ui.theme.EstiloCifra
import com.example.autenticacioncontinua.ui.theme.EstiloTeleprompter
import com.example.autenticacioncontinua.ui.theme.FamiliaApp
import com.example.autenticacioncontinua.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Toda la tipografía de la aplicación sale de [FamiliaApp].
 *
 * ### Por qué hace falta comprobarlo
 *
 * `Typography` de Material 3 tiene QUINCE huecos. La tabla de la aplicación
 * llenaba cinco, y los otros diez se quedaban con los de fábrica —que traen
 * `FontFamily.Default`—. El efecto no se ve escribiendo el código: se ve en la
 * pantalla, donde un `bodySmall` o el título de un `AlertDialog` salían con la
 * fuente del sistema mientras el resto usaba la de la aplicación.
 *
 * Y para este proyecto no es un detalle de estilo. La fuente del sistema la
 * sustituye el fabricante —MIUI trae la suya—, así que con dos terminales de
 * fabricantes distintos las métricas del texto cambian de un aparato a otro:
 * dónde caen los saltos de línea y cuánto texto ve el participante de un
 * vistazo, que es parte de la tarea que el diseño quiere mantener idéntica.
 * Empaquetar Inter (pendiente antes del campo) sólo arregla eso si TODOS los
 * huecos apuntan a la constante que se va a cambiar.
 *
 * Esta prueba es lo que hace que ese cambio de una línea alcance a la interfaz
 * entera en vez de a un tercio de ella.
 */
class TipografiaTest {

    /** Los quince huecos, nombrados para que el fallo diga cuál se escapó. */
    private val huecos = mapOf(
        "displayLarge" to Typography.displayLarge,
        "displayMedium" to Typography.displayMedium,
        "displaySmall" to Typography.displaySmall,
        "headlineLarge" to Typography.headlineLarge,
        "headlineMedium" to Typography.headlineMedium,
        "headlineSmall" to Typography.headlineSmall,
        "titleLarge" to Typography.titleLarge,
        "titleMedium" to Typography.titleMedium,
        "titleSmall" to Typography.titleSmall,
        "bodyLarge" to Typography.bodyLarge,
        "bodyMedium" to Typography.bodyMedium,
        "bodySmall" to Typography.bodySmall,
        "labelLarge" to Typography.labelLarge,
        "labelMedium" to Typography.labelMedium,
        "labelSmall" to Typography.labelSmall
    )

    @Test
    fun `los quince estilos de Material usan la familia de la aplicacion`() {
        val sueltos = huecos.filterValues { it.fontFamily != FamiliaApp }.keys
        assertTrue(
            "estos estilos no usan FamiliaApp y saldran con la fuente del " +
                "sistema, que cambia entre fabricantes: $sueltos",
            sueltos.isEmpty()
        )
    }

    /** Los dos estilos sueltos del minijuego, que no viven en la tabla. */
    @Test
    fun `los estilos del minijuego usan la familia de la aplicacion`() {
        assertEquals(FamiliaApp, EstiloTeleprompter.fontFamily)
        assertEquals(FamiliaApp, EstiloCifra.fontFamily)
    }

    /**
     * Y que la tabla esté completa: si Material añadiera un hueco nuevo en una
     * versión posterior, esta prueba seguiría en verde mirando sólo los quince
     * que conoce. El recuento la obliga a enterarse.
     */
    @Test
    fun `la tabla cubre los quince huecos que Material define`() {
        val declarados = Typography::class.java.declaredFields
            .filter { it.type == androidx.compose.ui.text.TextStyle::class.java }
            .size
        assertEquals(
            "Material define $declarados estilos y aqui se comprueban ${huecos.size}. " +
                "Si la version de Compose ha traido huecos nuevos, hay que darles " +
                "FamiliaApp y anadirlos a esta tabla.",
            declarados, huecos.size
        )
    }

    /**
     * Ningún estilo puede quedarse sin interlineado explícito.
     *
     * Con `lineHeight` sin especificar, Compose usa el de la fuente, que otra
     * vez depende del fichero de fuente y del fabricante. Es la misma variable
     * que la familia, por la puerta de al lado.
     */
    @Test
    fun `todos los estilos fijan su interlineado`() {
        val sinAlto = huecos.filterValues { it.lineHeight.isUnspecified }.keys
        assertTrue("estilos sin interlineado explicito: $sinAlto", sinAlto.isEmpty())
        assertTrue(EstiloTeleprompter.lineHeight.isSpecified)
        assertTrue(EstiloCifra.lineHeight.isSpecified)
    }
}
