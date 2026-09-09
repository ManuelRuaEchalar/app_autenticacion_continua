package com.example.autenticacioncontinua.domain.export

import java.security.MessageDigest

/**
 * La huella de lo exportado, para poder afirmar que lo que llegó al PC es lo
 * que salió del teléfono.
 *
 * ### Por qué SHA-256 y no «se copió y ya está»
 *
 * Porque la copia por USB de una tarjeta a un portátil falla en silencio más a
 * menudo de lo que parece: un cable que se mueve, una tarjeta con un sector
 * malo, una copia interrumpida que deja el fichero truncado con el tamaño
 * correcto en el listado. Ninguna de esas cosas avisa. Al final del estudio,
 * un fichero corrupto es una visita perdida que ya no se puede repetir porque
 * el participante hizo esa sesión hace dos meses.
 *
 * Con la huella escrita en el manifiesto, comprobar la copia es un comando en
 * el PC (`sha256sum`) y una comparación.
 *
 * ### El manifiesto se firma a sí mismo, y por eso su huella va fuera
 *
 * Cada CSV lleva su huella DENTRO del manifiesto, y la huella del zip entero se
 * muestra en la pantalla y se escribe en el nombre del fichero. Así hay dos
 * niveles: si falla el zip completo se sabe enseguida, y si falla una tabla
 * concreta se sabe cuál, que es la diferencia entre perder una visita y perder
 * un canal de una visita.
 */
object Manifiesto {

    const val NOMBRE = "manifiesto.json"

    /** SHA-256 en hexadecimal minúsculo, como lo imprime `sha256sum`. */
    fun huella(datos: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(datos)
            .joinToString("") { "%02x".format(it) }

    fun huella(texto: String): String = huella(texto.toByteArray(Charsets.UTF_8))

    /**
     * El manifiesto en JSON, escrito a mano.
     *
     * SIN LIBRERÍA DE JSON A PROPÓSITO: son ocho campos y dos de ellos son
     * listas de pares. Traer Gson o Moshi para esto añadiría un dependencia al
     * APK cuyo tamaño y consumo se están midiendo, y el escapado necesario aquí
     * —nombres de fichero y huellas hexadecimales— no tiene ningún caso raro.
     */
    fun json(
        seudonimo: String,
        sesionId: Long,
        visita: Int,
        dispositivoId: String,
        versionApp: String,
        versionProtocolo: String,
        exportadoMs: Long,
        tablas: Map<String, String>
    ): String {
        val filas = tablas.entries.joinToString(",\n") { (nombre, contenido) ->
            val lineas = contenido.count { it == '\n' } - 1   // sin la cabecera
            """    {"fichero": "${esc(nombre)}", "filas": $lineas, """ +
                """"sha256": "${huella(contenido)}"}"""
        }
        return """
{
  "formato": "sesion-controlada/1",
  "seudonimo": "${esc(seudonimo)}",
  "sesionId": $sesionId,
  "visita": $visita,
  "dispositivoId": "${esc(dispositivoId)}",
  "versionApp": "${esc(versionApp)}",
  "versionProtocolo": "${esc(versionProtocolo)}",
  "exportadoMs": $exportadoMs,
  "tablas": [
$filas
  ]
}
""".trimStart()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Nombre del paquete de una visita.
     *
     * LLEVA EL SEUDÓNIMO Y LA VISITA, no una marca de tiempo sola. Al final del
     * estudio habrá del orden de 250 ficheros en una carpeta, y con el nombre
     * hay que poder ver de un vistazo que a P07 le falta la visita 4 sin abrir
     * ninguno. La fecha va detrás para que dos exportaciones de la misma visita
     * —porque la primera falló a medias— no se pisen.
     */
    fun nombreDelPaquete(seudonimo: String, visita: Int, marca: String): String {
        val limpio = seudonimo.trim().ifBlank { "sin_id" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "sesion_${limpio}_v${visita}_$marca.zip"
    }
}
