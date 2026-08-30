package com.example.autenticacioncontinua.data.textos

import android.content.Context
import com.example.autenticacioncontinua.domain.textos.Parrafo
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos

/**
 * Carga el corpus empaquetado desde los assets.
 *
 * Un fichero por idioma, un párrafo por línea. El identificador se deriva del
 * NÚMERO DE LÍNEA (`es_0001`), y por eso el corpus está congelado: regenerarlo
 * cambiaría los identificadores ya guardados con las pulsaciones y no habría
 * forma de saber qué texto transcribió cada participante. Se genera una vez con
 * `analisis/preparar_textos.py`, se revisa y no se toca más.
 *
 * SE CARGA ENTERO EN MEMORIA, y es asumible: 2 316 párrafos de español y 803 de
 * latín, con una media de 265 caracteres, son unos 800 KB. A cambio, elegir un
 * párrafo no toca el disco a mitad de un bloque cronometrado.
 */
class CorpusDeTextos(private val context: Context) {

    private val cache = mutableMapOf<String, List<Parrafo>>()

    fun cargar(idioma: String): List<Parrafo> = cache.getOrPut(idioma) {
        context.assets.open("$CARPETA/$idioma.txt").bufferedReader().useLines { lineas ->
            lineas.map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { i, texto ->
                    Parrafo(id = "%s_%04d".format(idioma, i + 1), idioma = idioma, texto = texto)
                }
                .toList()
        }
    }

    fun selector(): SelectorDeParrafos =
        SelectorDeParrafos(SelectorDeParrafos.IDIOMAS.associateWith { cargar(it) })

    private companion object {
        const val CARPETA = "textos"
    }
}
