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
 * ### La deuda declarada
 *
 * `MainActivity.kt` trae su propia paleta oscura, de cuando la aplicación era
 * sólo la herramienta de recolección. Migrarla al sistema nuevo es la pantalla
 * P1 del plan y se hará con las pantallas del estudio; hasta entonces está en
 * [EXCEPCIONES] con su recuento exacto. **El recuento se comprueba**: si
 * aparecen literales nuevos ahí, la prueba falla igual. Una excepción sin
 * número es una puerta abierta.
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
         * MainActivity conserva la paleta oscura de la herramienta de
         * recolección (10 constantes). Se migra con las pantallas del estudio.
         */
        val EXCEPCIONES = mapOf("MainActivity.kt" to 10)
    }
}
