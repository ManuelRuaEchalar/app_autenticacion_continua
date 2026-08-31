package com.example.autenticacioncontinua.domain.juego

import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos

/**
 * El orden de las fases de una visita, decidido de antemano y entero.
 *
 * POR QUÉ SE CALCULA LA SESIÓN COMPLETA ANTES DE EMPEZAR, en vez de ir
 * decidiendo la fase siguiente cuando toca. Porque el reparto de idiomas
 * depende del NÚMERO DE SESIÓN, no del momento: si se decidiera sobre la
 * marcha, un fallo a mitad de la visita —la app muere y se reabre— podría
 * recalcular el reparto con un número de visita ya incrementado y darle al
 * participante dos bloques de latín, o ninguno. Con el guion fijado al abrir la
 * sesión, lo que se muestra está determinado desde el primer segundo y se puede
 * enseñar en la pantalla previa.
 *
 * Clase pura, sin Android: se prueba entera en la JVM.
 */
object GuionDeSesion {

    /**
     * Las fases de la visita [numeroSesion], en orden.
     *
     * @param numeroSesion empieza en 1. Es lo que fija la posición del latín.
     */
    fun fases(numeroSesion: Int, selector: SelectorDeParrafos): List<FaseDeSesion> {
        require(numeroSesion >= 1) { "numeroSesion=$numeroSesion: las visitas empiezan en 1" }
        val bloques = selector.idiomasDeSesion(numeroSesion)
            .mapIndexed { i, idioma -> FaseDeSesion.Bloque(indice = i, idioma = idioma) }
        return listOf(FaseDeSesion.Aclimatacion) + bloques + FaseDeSesion.Fin
    }

    /**
     * Cuánto dura la visita entera, sin contar el resumen final.
     *
     * Sirve para decírselo al participante antes de empezar. Con la
     * aclimatación de 10 s y tres bloques de 100 s son 5 min 10 s.
     */
    fun duracionTotalMs(numeroSesion: Int, selector: SelectorDeParrafos): Long =
        fases(numeroSesion, selector).sumOf { it.duracionMs }
}
