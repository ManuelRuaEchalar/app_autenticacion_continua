package com.example.autenticacioncontinua.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ningún color literal fuera de `ui/theme`.
 *
 * El plan pedía esto como «revisión de código». Una revisión manual de esta
 * regla se cumple el primer mes y se olvida el segundo; aquí la comprueba la
 * compilación. Y la regla no es cosmética: si los colores se reparten por las
 * pantallas, `ContrasteTest` deja de poder comprobar que todo lo que se lee
 * cumple el mínimo legible, porque ya no sabe qué combinaciones existen.
 *
 * ### La deuda declarada, saldada el 31/08
 *
 * `MainActivity.kt` traía su propia paleta oscura —diez constantes— de cuando
 * la aplicación era sólo la herramienta de recolección, y estaba en
 * [EXCEPCIONES] con su recuento exacto. Ya no: la pantalla P1 y las dos que
 * colgaban de ella (`ProtectionAndDiary.kt` y `LabeledCaptureScreen.kt`) leen
 * de `ui/theme`, y [EXCEPCIONES] está vacío.
 *
 * **SE DEJA EL MECANISMO, no sólo el mapa vacío.** La regla que se comprobaba
 * sobre esa deuda —un recuento exacto, nunca una excepción abierta— es la que
 * hace que declarar deuda sea barato y acumularla caro. La próxima pantalla que
 * llegue a medio migrar tiene dónde apuntarse, con número.
 */
class SinColoresLiteralesTest {

    @Test
    fun `no hay literales de color fuera del tema`() {
        val infractores = mutableListOf<String>()
        val porFichero = mutableMapOf<String, Int>()

        for (f in fuentes()) {
            val ruta = f.invariantSeparatorsPath
            if (ruta.contains(CARPETA_TEMA)) continue

            val n = f.readLines().withIndex().count { (_, linea) ->
                PATRON.containsMatchIn(linea) && !linea.trimStart().startsWith("//")
            }
            if (n == 0) continue

            porFichero[f.name] = n
            val permitidos = EXCEPCIONES[f.name]
            if (permitidos == null) {
                infractores += "${f.name}: $n literales de color"
            } else if (n > permitidos) {
                infractores += "${f.name}: $n literales, y la deuda declarada era " +
                    "$permitidos. No se anaden colores nuevos a lo que esta pendiente " +
                    "de migrar; se usan los tokens de ui/theme."
            }
        }

        assertTrue(
            "colores literales fuera de ui/theme:\n" + infractores.joinToString("\n") { "  - $it" },
            infractores.isEmpty()
        )
    }

    /**
     * Y al revés: si la deuda se salda, la excepción sobra y hay que quitarla.
     * Sin esto, la lista de excepciones sobreviviría a la migración y volvería
     * a abrir la puerta sin que nadie se diera cuenta.
     */
    @Test
    fun `las excepciones declaradas siguen existiendo`() {
        for ((nombre, permitidos) in EXCEPCIONES) {
            val f = fuentes().firstOrNull { it.name == nombre }
            if (f == null) {
                assertTrue("`$nombre` ya no existe: quita su excepcion", false)
                continue
            }
            val n = f.readLines().count { PATRON.containsMatchIn(it) }
            assertTrue(
                "`$nombre` ya no tiene literales de color ($n de $permitidos): " +
                    "la migracion esta hecha, quita su excepcion de EXCEPCIONES",
                n > 0
            )
        }
    }

    private fun fuentes(): List<File> =
        File(RAIZ).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        const val RAIZ = "src/main/java/com/example/autenticacioncontinua"
        const val CARPETA_TEMA = "/ui/theme/"

        /** `Color(0x...)` y `Color(r, g, b)`. */
        val PATRON = Regex("""\bColor\(\s*(0x[0-9a-fA-F]{6,8}|\d+\s*,)""")

        /**
         * Fichero -> cuántos literales se toleran mientras dure la migración.
         *
         * VACÍO, y ése es el estado correcto. La única entrada que tuvo fue
         * `MainActivity.kt` con sus 10 constantes de la paleta oscura, y se
         * quitó el 31/08 al migrarla. Añadir aquí un fichero es declarar deuda:
         * se admite, con número, y con la fecha en que se piensa saldar.
         */
        val EXCEPCIONES = emptyMap<String, Int>()
    }
}
