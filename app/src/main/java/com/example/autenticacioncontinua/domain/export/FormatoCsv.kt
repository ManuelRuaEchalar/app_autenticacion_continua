package com.example.autenticacioncontinua.domain.export

import java.util.Locale

/**
 * Lectura y escritura de CSV, con la única garantía que importa aquí: lo que se
 * escribe se vuelve a leer idéntico.
 *
 * ### Por qué hay un CSV propio y no una librería
 *
 * Porque las tres cosas que este proyecto necesita del formato son justo las
 * que una librería genérica deja a la configuración, y equivocarse en
 * cualquiera de ellas produce un fichero que PARECE correcto:
 *
 * 1. **El separador decimal.** `Float.toString()` en un teléfono con la
 *    configuración regional española escribe `9,64`. Dentro de un CSV separado
 *    por comas, eso parte la fila en dos columnas y la desplaza entera. El
 *    fichero se abre, se lee, tiene el número de filas correcto, y todas las
 *    columnas a partir de ahí están corridas. Aquí se fuerza [Locale.ROOT] en
 *    todas las conversiones.
 * 2. **El carácter tecleado puede ser una coma o una comilla.** El corpus las
 *    tiene: `eventos_tecleo.esperado` guarda literalmente lo que había que
 *    escribir, y en la disposición del teclado hay una tecla de coma y otra de
 *    punto. Un escapado a medias las convierte en separadores.
 * 3. **Distinguir el nulo del vacío.** `presion` y `area` son nulos cuando el
 *    terminal no los mide, y `x`/`y` cuando la coordenada venía de otra tecla
 *    (ver `CoordenadaDeTecla`). Un nulo que se lea como `0` sería un dato
 *    inventado: diría «no hay presión» donde el dato es «no se sabe».
 *
 * ### La convención de nulos
 *
 * **Campo vacío = nulo.** Se puede aplicar sin ambigüedad porque sólo los
 * campos NUMÉRICOS son anulables en este corpus, y un número nunca es la cadena
 * vacía. Los campos de texto —seudónimo, idioma, carácter— nunca son nulos, así
 * que un vacío en ellos es un vacío de verdad y se escribe entre comillas.
 */
object FormatoCsv {

    const val SEPARADOR = ','
    const val FIN_DE_LINEA = "\n"

    /** Una celda, ya escapada si le hacía falta. */
    fun celda(valor: String?): String {
        if (valor == null) return ""
        val necesitaComillas = valor.isEmpty() ||
            valor.any { it == SEPARADOR || it == '"' || it == '\n' || it == '\r' } ||
            valor != valor.trim()
        if (!necesitaComillas) return valor
        return "\"" + valor.replace("\"", "\"\"") + "\""
    }

    /**
     * Un número, siempre con punto decimal.
     *
     * `Float` y no `Double` en la firma de los que vienen del sensor: escribir
     * un float como double añade dígitos que no estaban en la medida
     * (`9.64f.toDouble()` es 9.640000343322754) y da una precisión falsa.
     */
    fun celda(valor: Float?): String =
        if (valor == null) "" else String.format(Locale.ROOT, "%s", valor.toString())

    fun celda(valor: Double?): String =
        if (valor == null) "" else String.format(Locale.ROOT, "%s", valor.toString())

    fun celda(valor: Long?): String = valor?.toString() ?: ""
    fun celda(valor: Int?): String = valor?.toString() ?: ""

    /** El booleano va como 0/1: es lo que ya guarda SQLite y lo que lee pandas. */
    fun celda(valor: Boolean?): String = when (valor) {
        null -> ""
        true -> "1"
        false -> "0"
    }

    fun fila(celdas: List<String>): String = celdas.joinToString(SEPARADOR.toString()) + FIN_DE_LINEA

    /**
     * Deshace [fila]. Devuelve las celdas con su nulo distinguido del vacío.
     *
     * Existe para que la prueba de ida y vuelta sea posible: la fase 9 exige
     * que «el fichero exportado se vuelve a leer y coincide fila a fila», y eso
     * no se puede afirmar sin un lector que sea el reverso exacto del escritor.
     */
    fun leerFila(linea: String): List<String?> {
        val celdas = mutableListOf<String?>()
        val actual = StringBuilder()
        var dentroDeComillas = false
        var entrecomillada = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                dentroDeComillas && c == '"' && i + 1 < linea.length && linea[i + 1] == '"' -> {
                    actual.append('"'); i++
                }
                c == '"' -> {
                    dentroDeComillas = !dentroDeComillas
                    if (dentroDeComillas) entrecomillada = true
                }
                c == SEPARADOR && !dentroDeComillas -> {
                    celdas += if (actual.isEmpty() && !entrecomillada) null else actual.toString()
                    actual.clear(); entrecomillada = false
                }
                else -> actual.append(c)
            }
            i++
        }
        celdas += if (actual.isEmpty() && !entrecomillada) null else actual.toString()
        return celdas
    }

    /** Todas las filas de un CSV con cabecera. La cabecera NO se devuelve. */
    fun leer(texto: String): List<List<String?>> =
        texto.split(FIN_DE_LINEA)
            .drop(1)
            .filter { it.isNotEmpty() }
            .map { leerFila(it) }

    fun cabecera(texto: String): List<String?> =
        leerFila(texto.substringBefore(FIN_DE_LINEA))
}
