package com.example.autenticacioncontinua.textos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprueba el corpus QUE SE VA A EMPAQUETAR, no uno de mentira.
 *
 * Lee los ficheros reales de `assets/textos/` y verifica lo que la fase 5 pide:
 * longitudes dentro de límites y ni un solo carácter fuera del teclado del
 * minijuego.
 *
 * POR QUÉ SE COMPRUEBA AQUÍ SI YA LO COMPRUEBA EL GENERADOR. Porque el
 * generador es un guion de Python que se ejecuta a mano y el corpus es un
 * fichero que se puede editar. Si alguien corrige una errata a mano y mete unas
 * comillas, el teclado del minijuego no tendría esa tecla y el participante se
 * quedaría atascado a mitad de un bloque cronometrado, sin poder avanzar. Eso
 * se descubre aquí y no con una persona delante.
 */
class CorpusEmpaquetadoTest {

    @Test
    fun `el corpus existe y esta empaquetado`() {
        for (idioma in IDIOMAS) {
            val f = fichero(idioma)
            assertTrue(
                "falta ${f.path}. Se genera con `python analisis/preparar_textos.py`",
                f.exists()
            )
            assertTrue("${f.name} esta vacio", f.length() > 0)
        }
    }

    @Test
    fun `todos los parrafos caben en los limites de longitud`() {
        for (idioma in IDIOMAS) {
            val malos = parrafos(idioma)
                .filter { it.length !in MIN_CARACTERES..MAX_CARACTERES }
                .take(3)
            assertTrue(
                "$idioma: parrafos fuera de [$MIN_CARACTERES, $MAX_CARACTERES]: " +
                    malos.joinToString { "${it.length} car: '${it.take(40)}...'" },
                malos.isEmpty()
            )
        }
    }

    /**
     * LA COMPROBACIÓN QUE MÁS IMPORTA. Un carácter que el teclado no tiene deja
     * al participante sin poder avanzar: el motor espera esa tecla y no existe.
     */
    @Test
    fun `ningun parrafo usa un caracter que el teclado no tiene`() {
        for ((idioma, permitidos) in ALFABETOS) {
            val fuera = parrafos(idioma)
                .flatMap { it.toSet() }
                .toSet()
                .filterNot { it in permitidos }
            assertTrue(
                "$idioma usa caracteres que el teclado no tiene: " +
                    fuera.joinToString { "'$it' (U+%04X)".format(it.code) },
                fuera.isEmpty()
            )
        }
    }

    @Test
    fun `no hay parrafos duplicados`() {
        for (idioma in IDIOMAS) {
            val p = parrafos(idioma)
            assertEquals("$idioma tiene parrafos repetidos", p.size, p.toSet().size)
        }
    }

    /**
     * Hace falta texto para diez sesiones de UN participante sin repetir. No
     * para 25: dos personas distintas sí pueden ver el mismo texto, y conviene
     * que lo vean — si cada una transcribiera textos distintos, la dificultad
     * del texto quedaría confundida con la persona.
     */
    @Test
    fun `hay texto de sobra para las diez sesiones de un participante`() {
        val necesarios = mapOf("es" to 10 * 2 * 12, "la" to 10 * 1 * 12)
        for ((idioma, n) in necesarios) {
            val hay = parrafos(idioma).size
            assertTrue(
                "$idioma: hacen falta $n parrafos por participante y solo hay $hay",
                hay >= n
            )
        }
    }

    /**
     * El español usa `ñ` y vocales acentuadas que el latín no tiene, así que
     * los dos idiomas ejercitan teclas distintas. NO es un defecto —es
     * intrínseco a los idiomas— pero es una covariable que hay que poder
     * declarar en la memoria, así que se imprime.
     */
    @Test
    fun `informa del reparto de caracteres de cada idioma`() {
        for (idioma in IDIOMAS) {
            val p = parrafos(idioma)
            val cuenta = p.flatMap { it.toList() }.groupingBy { it }.eachCount()
            val total = cuenta.values.sum()
            val top = cuenta.entries.sortedByDescending { it.value }.take(6)
            println(
                "%s: %d parrafos, %d caracteres distintos, media %d car/parrafo".format(
                    idioma, p.size, cuenta.size, p.sumOf { it.length } / p.size
                )
            )
            println("   " + top.joinToString(" ") {
                "'%s' %.1f%%".format(if (it.key == ' ') "␣" else it.key.toString(),
                    100.0 * it.value / total)
            })
        }
    }

    // ------------------------------------------------------------------

    private fun fichero(idioma: String) = File("$RAIZ/$idioma.txt")

    private fun parrafos(idioma: String): List<String> =
        fichero(idioma).readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val RAIZ = "src/main/assets/textos"
        const val MIN_CARACTERES = 180
        const val MAX_CARACTERES = 320

        val IDIOMAS = listOf("es", "la")

        /**
         * Tiene que coincidir con `analisis/preparar_textos.py` Y con las teclas
         * que dibuje el teclado del minijuego. Los tres sitios se comprueban
         * entre sí: este test contra el corpus, y el del teclado contra esto.
         */
        const val PUNTUACION = " ,."
        val ALFABETOS = mapOf(
            "es" to ("abcdefghijklmnopqrstuvwxyzñáéíóúü" + PUNTUACION).toSet(),
            "la" to ("abcdefghijklmnopqrstuvwxyz" + PUNTUACION).toSet()
        )
    }
}
