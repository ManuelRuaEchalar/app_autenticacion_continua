package com.example.autenticacioncontinua.data.tecleo

import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.domain.tecleo.DetectorDeConstante
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda

/** Cómo va el bloque ahora mismo. Es lo que pinta la pantalla. */
data class EstadoDeTecleo(
    /** Posición del carácter que toca escribir. */
    val posicion: Int,
    val pulsaciones: Int,
    val aciertos: Int,
    val errores: Int,
    val borrados: Int,
    /** Índices del párrafo escritos mal y aún sin corregir. */
    val fallados: Set<Int>,
    val ppmBruta: Float,
    val ppmNeta: Float,
    val precision: Float,
    val terminado: Boolean
)

/**
 * Convierte pulsaciones en eventos y en la puntuación del bloque.
 *
 * TODA LA LÓGICA DEL MINIJUEGO ESTÁ AQUÍ, y es una clase pura: sin Android, sin
 * relojes propios, sin corrutinas. La pantalla sólo dibuja lo que esta clase
 * dice, y el motor de sesión sólo guarda lo que produce. Así, la parte que
 * decide qué es un acierto y qué es un error —de la que salen la precisión y
 * las pulsaciones por minuto de los 20-30 participantes— se prueba entera en la
 * JVM y en milisegundos.
 *
 * ### Reglas, y por qué son éstas
 *
 * **Un error AVANZA y queda marcado.** Es la convención de las pruebas de
 * mecanografía, y la alternativa —bloquear hasta que se acierte— tiene dos
 * problemas: frustra al participante, y convierte una errata en una ráfaga de
 * pulsaciones repetidas que no es dinámica de tecleo sino pelea con la
 * interfaz. El participante puede corregir con retroceso si quiere; que corrija
 * o no es en sí un rasgo de la persona.
 *
 * **El retroceso se cuenta aparte, ni como acierto ni como error.** Son cosas
 * distintas: el error es un carácter que no coincidió con el esperado; el
 * borrado es la corrección. Dos participantes con la misma precisión final
 * pueden tener dinámicas opuestas —uno que no falla nunca y otro que falla y
 * corrige— y eso hay que poder verlo.
 *
 * **Nunca se guarda texto libre.** [esperado] y [recibido] son caracteres de un
 * párrafo del corpus empaquetado que se le mostró al participante. No hay nada
 * que escriba por su cuenta, así que no hay contenido personal que pueda acabar
 * en la base.
 *
 * **La pulsación se registra al soltar, no al pulsar.** El evento sólo está
 * completo cuando se conoce `tUp`, del que sale el tiempo de permanencia. Una
 * tecla que siga pulsada al acabar el bloque se emite igualmente, con `tUpMs`
 * a 0 — dato incompleto, pero declarado como tal.
 */
class RegistroDeTecleo(
    private val bloqueId: Long,
    private val parrafoId: String,
    private val textoEsperado: String,
    /** Instante de inicio del bloque, para las pulsaciones por minuto. */
    private val inicioMs: Long
) {

    private val eventos = mutableListOf<EventoTecleoEntity>()
    private val abajoPorCaracter = HashMap<String, PulsacionCruda>()

    private var posicion = 0
    private var pulsaciones = 0
    private var aciertos = 0
    private var errores = 0
    private var borrados = 0
    private val fallados = linkedSetOf<Int>()

    /** Detectores de canal muerto. Ver [DetectorDeConstante]. */
    val detectorPresion = DetectorDeConstante("presion")
    val detectorArea = DetectorDeConstante("area")

    val estado: EstadoDeTecleo
        get() = EstadoDeTecleo(
            posicion = posicion,
            pulsaciones = pulsaciones,
            aciertos = aciertos,
            errores = errores,
            borrados = borrados,
            fallados = fallados.toSet(),
            ppmBruta = ppm(aciertos + errores),
            ppmNeta = ppm(aciertos),
            precision = if (pulsaciones == 0) 0f else aciertos.toFloat() / pulsaciones,
            terminado = posicion >= textoEsperado.length
        )

    /** El carácter que toca escribir, o `null` si el párrafo se acabó. */
    val caracterActual: String?
        get() = textoEsperado.getOrNull(posicion)?.toString()

    /**
     * Procesa una pulsación.
     *
     * @return el evento cerrado, si esta pulsación cerró uno (fase ARRIBA).
     */
    fun aceptar(p: PulsacionCruda): EventoTecleoEntity? {
        if (p.fase == FaseDePulsacion.ABAJO) {
            abajoPorCaracter[claveDe(p)] = p
            return null
        }

        // Un ARRIBA sin su ABAJO ocurre si el dedo entró en la tecla desde
        // fuera, o si la pulsación empezó antes del bloque. Se descarta en vez
        // de inventarle un tDown: un tiempo de permanencia falso contamina
        // justo la magnitud más valiosa.
        val abajo = abajoPorCaracter.remove(claveDe(p)) ?: return null

        detectorPresion.observar(abajo.presion)
        detectorArea.observar(abajo.area)

        val evento = if (p.esRetroceso) registrarRetroceso(abajo, p)
        else registrarCaracter(abajo, p)

        eventos += evento
        return evento
    }

    private fun registrarRetroceso(
        abajo: PulsacionCruda,
        arriba: PulsacionCruda
    ): EventoTecleoEntity {
        borrados++
        // Al principio del párrafo el retroceso no tiene nada que borrar. Se
        // registra igualmente: la intención de corregir es parte de la
        // dinámica, aunque no cambie el texto.
        if (posicion > 0) {
            posicion--
            fallados.remove(posicion)
        }
        return evento(abajo, arriba, esperado = "", recibido = "", acierto = false, borrado = true)
    }

    private fun registrarCaracter(
        abajo: PulsacionCruda,
        arriba: PulsacionCruda
    ): EventoTecleoEntity {
        val esperado = caracterActual
        if (esperado == null) {
            // Párrafo terminado: se sigue registrando la pulsación pero no
            // avanza nada. No se descarta porque el tecleo posterior al final
            // del texto también ocurrió.
            pulsaciones++
            return evento(abajo, arriba, esperado = "", recibido = abajo.caracter, acierto = false)
        }

        val acierto = abajo.caracter == esperado
        pulsaciones++
        if (acierto) aciertos++ else { errores++; fallados += posicion }
        val posicionDelEvento = posicion
        posicion++

        return evento(
            abajo, arriba,
            esperado = esperado,
            recibido = abajo.caracter,
            acierto = acierto,
            posicionForzada = posicionDelEvento
        )
    }

    private fun evento(
        abajo: PulsacionCruda,
        arriba: PulsacionCruda,
        esperado: String,
        recibido: String,
        acierto: Boolean,
        borrado: Boolean = false,
        posicionForzada: Int = posicion
    ) = EventoTecleoEntity(
        bloqueId = bloqueId,
        parrafoId = parrafoId,
        posicion = posicionForzada,
        esperado = esperado,
        recibido = recibido,
        acierto = acierto,
        borrado = borrado,
        tDownMs = abajo.tMs,
        tUpMs = arriba.tMs,
        // Los canales de contacto se toman del ABAJO: es el instante del
        // impacto del dedo, y es donde la presion y el area significan algo.
        // Al soltar, el dedo ya se esta separando y el area se desploma.
        x = abajo.x, y = abajo.y,
        presion = abajo.presion, area = abajo.area
    )

    /**
     * Cierra el bloque y devuelve todos los eventos.
     *
     * Las teclas que sigan pulsadas se emiten con `tUpMs = 0`: sin tiempo de
     * permanencia, pero registradas. Perderlas sesgaría la muestra hacia las
     * pulsaciones cortas, que son justo las que sí se cerraron a tiempo.
     */
    fun cerrar(): List<EventoTecleoEntity> {
        for ((_, abajo) in abajoPorCaracter) {
            eventos += EventoTecleoEntity(
                bloqueId = bloqueId,
                parrafoId = parrafoId,
                posicion = posicion,
                esperado = "",
                recibido = abajo.caracter,
                acierto = false,
                borrado = abajo.esRetroceso,
                tDownMs = abajo.tMs,
                tUpMs = 0L,
                x = abajo.x, y = abajo.y,
                presion = abajo.presion, area = abajo.area
            )
        }
        abajoPorCaracter.clear()
        return eventos.toList()
    }

    /** Los eventos acumulados hasta ahora, sin cerrar el bloque. */
    fun eventos(): List<EventoTecleoEntity> = eventos.toList()

    /**
     * Pulsaciones por minuto, con la convención de **5 caracteres = 1 palabra**.
     *
     * Se calcula con el reloj del último evento y no con «ahora» para que el
     * valor sea reproducible: dos llamadas seguidas sobre el mismo estado tienen
     * que dar el mismo número, o la cifra que se guarda depende de cuándo se
     * consultó.
     */
    private fun ppm(caracteres: Int): Float {
        val ultimo = eventos.lastOrNull()?.tDownMs ?: return 0f
        val minutos = (ultimo - inicioMs) / 60_000f
        if (minutos <= 0f) return 0f
        return (caracteres / 5f) / minutos
    }

    /**
     * Clave para emparejar un ARRIBA con su ABAJO.
     *
     * Se empareja por TECLA y no por identificador de puntero: en un teclado en
     * pantalla, dos dedos pueden estar sobre dos teclas a la vez —tecleo a dos
     * manos, que es lo normal—, y el puntero que baja no siempre es el mismo
     * índice que el que sube. La tecla sí identifica sin ambigüedad, salvo que
     * se pulse la misma dos veces simultáneamente, que no ocurre con una mano
     * por tecla.
     */
    private fun claveDe(p: PulsacionCruda) =
        if (p.esRetroceso) CLAVE_RETROCESO else p.caracter

    companion object {
        /**
         * Clave interna del retroceso.
         *
         * NO puede ser la cadena vacia: las teclas de funcion tambien traen
         * [PulsacionCruda.caracter] vacio, y colisionarian con el retroceso
         * al emparejar un ARRIBA con su ABAJO — el `up` de una mayuscula
         * cerraria la pulsacion de un borrado. Se usa el codigo de control
         * de retroceso, que ninguna tecla de caracter produce. Va escrito
         * como secuencia de escape a proposito: el caracter literal es
         * invisible en el editor y nadie sabria que esta ahi.
         */
        const val CLAVE_RETROCESO = "\u0008"
    }
}
