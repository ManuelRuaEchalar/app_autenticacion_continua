package com.example.autenticacioncontinua.domain.textos

import kotlin.random.Random

/**
 * Un párrafo del corpus empaquetado.
 *
 * El [id] es `<idioma>_<indice>` sobre el fichero de recursos, y es lo que se
 * guarda con cada pulsación. **El corpus está congelado**: se genera una vez
 * con `analisis/preparar_textos.py` antes del campo y no se vuelve a tocar. Si
 * se regenerara a mitad de los 60 días, los identificadores ya guardados
 * apuntarían a textos distintos y no habría forma de saber qué transcribió
 * cada quien.
 */
data class Parrafo(
    val id: String,
    val idioma: String,
    val texto: String
)

/**
 * Decide qué idioma toca en cada bloque y qué párrafos se muestran.
 *
 * ### Dos bloques en español y uno en latín
 *
 * El latín está para separar **teclear** de **entender lo que se teclea**. Un
 * participante que comprende el texto lee por palabras y anticipa; con un
 * idioma que no entiende, lee por letras. Es la misma persona con dos regímenes
 * de tecleo distintos, y tenerlos a los dos permite comprobar cuál discrimina
 * mejor la identidad — que no es obvio de antemano.
 *
 * ### Por qué el latín ROTA de posición entre sesiones
 *
 * Si el bloque de latín fuera siempre el tercero, cargaría con todo el
 * cansancio de los quince minutos anteriores, y «efecto del idioma» y «efecto
 * de la fatiga» serían indistinguibles. Rotando su posición —primera sesión en
 * el bloque 0, segunda en el 1, tercera en el 2, y vuelta a empezar— cada
 * posición acumula latín y español por igual a lo largo de las diez sesiones.
 *
 * Es el mismo argumento que el contrabalanceo del orden de dispositivos, y el
 * mismo que el del protocolo de medición de recursos: **lo que no se
 * contrabalancea, se confunde.**
 *
 * ### Reproducibilidad
 *
 * Todo depende de la semilla de la sesión, que se guarda en
 * `sesiones_controladas.semillaSeleccion`. Con ella se puede reconstruir meses
 * después la secuencia exacta de textos que vio un participante sin haberla
 * almacenado entera.
 *
 * Clase pura: se prueba en la JVM.
 */
class SelectorDeParrafos(
    private val corpus: Map<String, List<Parrafo>>
) {

    init {
        require(IDIOMAS.all { corpus[it]?.isNotEmpty() == true }) {
            "el corpus debe traer parrafos de ${IDIOMAS.joinToString()}; " +
                "trae ${corpus.mapValues { it.value.size }}"
        }
    }

    /**
     * Qué idioma toca en el bloque [indiceBloque] de la sesión [numeroSesion].
     *
     * @param numeroSesion empieza en 1.
     * @param indiceBloque 0, 1 o 2.
     */
    fun idiomaDeBloque(numeroSesion: Int, indiceBloque: Int): String {
        require(numeroSesion >= 1) { "numeroSesion=$numeroSesion: empieza en 1" }
        require(indiceBloque in 0 until BLOQUES_POR_SESION) { "indiceBloque=$indiceBloque" }
        val posicionDelLatin = (numeroSesion - 1) % BLOQUES_POR_SESION
        return if (indiceBloque == posicionDelLatin) LATIN else ESPANOL
    }

    /** El reparto completo de una sesión, para enseñarlo antes de empezar. */
    fun idiomasDeSesion(numeroSesion: Int): List<String> =
        (0 until BLOQUES_POR_SESION).map { idiomaDeBloque(numeroSesion, it) }

    /**
     * Párrafos para un bloque, en el orden en que se mostrarán.
     *
     * Devuelve [cuantos] párrafos que el participante NO haya visto todavía. Se
     * piden de más a propósito —un bloque de cinco minutos consume entre tres y
     * nueve— porque quedarse sin texto a mitad de bloque obligaría a cortar la
     * tarea, y ese bloque valdría menos que los demás.
     *
     * SI SE AGOTAN LOS NO VISTOS, SE REUTILIZAN LOS MÁS ANTIGUOS en vez de
     * fallar: con diez sesiones por participante eso no debería ocurrir —hay
     * doce veces el texto necesario— pero si ocurriera, repetir un párrafo es
     * mucho mejor que dejar a alguien mirando una pantalla vacía. Quien llame
     * puede detectarlo comparando con [quedanSinVer].
     */
    fun parrafosPara(
        idioma: String,
        semillaSesion: Long,
        indiceBloque: Int,
        yaVistos: Set<String>,
        cuantos: Int = PARRAFOS_POR_BLOQUE
    ): List<Parrafo> {
        val todos = corpus[idioma] ?: error("idioma desconocido: $idioma")
        val libres = todos.filterNot { it.id in yaVistos }

        // La semilla combina sesión y bloque: si sólo dependiera de la sesión,
        // los tres bloques de una misma sesión barajarían igual y el segundo
        // empezaría por el mismo párrafo que el primero.
        val azar = Random(semillaSesion * 31 + indiceBloque)

        if (libres.size >= cuantos) return libres.shuffled(azar).take(cuantos)

        // Reserva: primero todos los libres, y se completa con vistos.
        val completar = todos.filter { it.id in yaVistos }.shuffled(azar)
        return (libres.shuffled(azar) + completar).take(cuantos)
    }

    fun quedanSinVer(idioma: String, yaVistos: Set<String>): Int =
        (corpus[idioma] ?: emptyList()).count { it.id !in yaVistos }

    companion object {
        const val ESPANOL = "es"
        const val LATIN = "la"
        val IDIOMAS = listOf(ESPANOL, LATIN)

        const val BLOQUES_POR_SESION = 3

        /**
         * Doce párrafos por bloque.
         *
         * Un bloque de cinco minutos consume entre tres —a 25 ppm— y nueve —a
         * 70 ppm—. Doce deja margen para el participante más rápido sin que la
         * lista sea absurda.
         */
        const val PARRAFOS_POR_BLOQUE = 12
    }
}
