package com.example.autenticacioncontinua.domain.juego

import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.tecleo.RegistroDeTecleo
import com.example.autenticacioncontinua.domain.tecleo.DetectorDeConstante
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.domain.textos.Parrafo

/** Lo que la pantalla del minijuego necesita saber para pintarse. */
data class EstadoDeBloque(
    /** Texto que se está transcribiendo ahora mismo. */
    val texto: String,
    /** Posición del carácter que toca escribir dentro de [texto]. */
    val posicion: Int,
    /** Índices de [texto] escritos mal y aún sin corregir. */
    val fallados: Set<Int>,
    /** Cuántos párrafos van completados, para la sensación de avance. */
    val parrafosCompletados: Int,

    // Totales del BLOQUE, no del párrafo: es lo que se guarda en `bloques`.
    val pulsaciones: Int,
    val aciertos: Int,
    val errores: Int,
    val borrados: Int,
    val ppm: Float,
    val precision: Float
)

/**
 * Un bloque de tecleo: varios párrafos encadenados bajo un mismo cronómetro.
 *
 * POR QUÉ EXISTE ESTA CLASE SI YA ESTÁ [RegistroDeTecleo]. Porque son dos
 * unidades distintas y confundirlas falsearía las cifras que se guardan.
 * `RegistroDeTecleo` es el motor de UN PÁRRAFO: sabe qué carácter toca, cuál
 * llegó y dónde está el cursor. El bloque es la UNIDAD DE ANÁLISIS —lo que se
 * remuestrea para los intervalos de confianza, según el diagnóstico del
 * 17-18/08— y dura un tiempo fijo que abarca entre tres y nueve párrafos.
 *
 * Si las pulsaciones por minuto se calcularan por párrafo y se promediaran, un
 * participante rápido que completa nueve párrafos pesaría igual que uno lento
 * que completa tres, y el promedio de promedios no es la tasa del bloque. Aquí
 * los totales se acumulan sobre el bloque entero y la tasa se calcula una sola
 * vez, al final, sobre esos totales.
 *
 * UN PÁRRAFO TERMINADO NO TERMINA EL BLOQUE: se encadena el siguiente sin pausa.
 * El bloque sólo lo cierra el reloj (ver [RelojBloque]).
 *
 * SI SE ACABAN LOS PÁRRAFOS se sigue sobre el último. No debería pasar —se piden
 * doce y el más rápido gasta nueve— pero si pasara, `RegistroDeTecleo` registra
 * igualmente las pulsaciones posteriores al final del texto, y eso es mejor que
 * dejar al participante mirando una pantalla vacía con el cronómetro corriendo.
 *
 * Clase pura, sin Android ni relojes propios: se prueba entera en la JVM.
 */
class MotorBloque(
    private val bloqueId: Long,
    val idioma: String,
    private val parrafos: List<Parrafo>,
    /** Inicio del bloque, para las pulsaciones por minuto. */
    private val inicioMs: Long
) {

    init {
        require(parrafos.isNotEmpty()) { "un bloque necesita al menos un parrafo" }
    }

    /**
     * Detectores de canal muerto, a nivel de BLOQUE y no de párrafo.
     *
     * [DetectorDeConstante] exige 30 pulsaciones antes de dictaminar, y un
     * párrafo de 180-320 caracteres las da de sobra — pero reiniciarlos en cada
     * párrafo tiraría la evidencia acumulada y haría que el veredicto dependiera
     * de dónde cae el corte. El bloque entero es la ventana correcta.
     */
    val detectorPresion = DetectorDeConstante("presion")
    val detectorArea = DetectorDeConstante("area")

    private var indice = 0
    private var registro = registroDe(parrafos[0])
    /** Eventos que ya se han sacado del registro actual, para no duplicarlos. */
    private var emitidosDelActual = 0

    private val eventos = mutableListOf<EventoTecleoEntity>()
    private val usados = linkedSetOf(parrafos[0].id)

    // Totales de los párrafos YA cerrados. Los del actual se suman al vuelo.
    private var pulsacionesCerradas = 0
    private var aciertosCerrados = 0
    private var erroresCerrados = 0
    private var borradosCerrados = 0

    private fun registroDe(p: Parrafo) = RegistroDeTecleo(
        bloqueId = bloqueId,
        parrafoId = p.id,
        textoEsperado = p.texto,
        inicioMs = inicioMs
    )

    /** El párrafo que se está transcribiendo. */
    val parrafoActual: Parrafo get() = parrafos[indice]

    /** Identificadores de los párrafos MOSTRADOS, en orden. */
    fun parrafosUsados(): List<String> = usados.toList()

    /** El carácter que toca escribir, o `null` si se acabó el texto disponible. */
    val caracterActual: String? get() = registro.caracterActual

    /**
     * Los totales quedan CONGELADOS al cerrar el bloque.
     *
     * No es una optimización, es corrección: `RegistroDeTecleo.cerrar()` no
     * pone a cero sus contadores, así que en cuanto [cerrarRegistroActual] los
     * suma a los acumulados, volver a leer el estado los contaría dos veces y el
     * bloque quedaría guardado con el doble de pulsaciones. Y además es lo
     * semánticamente correcto: un bloque terminado ya no cambia de cifras.
     */
    private var congelado: EstadoDeBloque? = null

    val estado: EstadoDeBloque
        get() {
            congelado?.let { return it }
            val vivo = registro.estado
            val pulsaciones = pulsacionesCerradas + vivo.pulsaciones
            val aciertos = aciertosCerrados + vivo.aciertos
            return EstadoDeBloque(
                texto = parrafoActual.texto,
                posicion = vivo.posicion,
                fallados = vivo.fallados,
                parrafosCompletados = indice,
                pulsaciones = pulsaciones,
                aciertos = aciertos,
                errores = erroresCerrados + vivo.errores,
                borrados = borradosCerrados + vivo.borrados,
                ppm = ppm(aciertos),
                precision = if (pulsaciones == 0) 0f else aciertos.toFloat() / pulsaciones
            )
        }

    /**
     * Procesa una pulsación y devuelve los eventos que cerró.
     *
     * Devuelve una lista y no un evento suelto porque la pulsación que completa
     * un párrafo cierra además ese párrafo, y con él pueden salir teclas que
     * seguían pulsadas.
     */
    fun aceptar(p: PulsacionCruda): List<EventoTecleoEntity> {
        val evento = registro.aceptar(p) ?: return emptyList()
        emitidosDelActual++
        observar(evento)
        eventos += evento

        val salida = mutableListOf(evento)
        if (registro.estado.terminado && indice < parrafos.lastIndex) {
            salida += avanzarParrafo()
        }
        return salida
    }

    /**
     * Cierra el párrafo actual y arranca el siguiente.
     *
     * Los totales del párrafo que se va se congelan aquí; a partir de este punto
     * el registro nuevo cuenta desde cero y el bloque sigue sumando.
     */
    private fun avanzarParrafo(): List<EventoTecleoEntity> {
        val colgantes = cerrarRegistroActual()

        indice++
        registro = registroDe(parrafos[indice])
        emitidosDelActual = 0
        usados += parrafos[indice].id
        return colgantes
    }

    /**
     * Vacía el registro actual y devuelve SÓLO lo que aún no se había emitido.
     *
     * `RegistroDeTecleo.cerrar()` devuelve todos sus eventos, incluidos los que
     * ya salieron por [aceptar]; sin este descarte, cada párrafo se guardaría
     * dos veces y las pulsaciones del bloque saldrían dobladas.
     */
    private fun cerrarRegistroActual(): List<EventoTecleoEntity> {
        val vivo = registro.estado
        pulsacionesCerradas += vivo.pulsaciones
        aciertosCerrados += vivo.aciertos
        erroresCerrados += vivo.errores
        borradosCerrados += vivo.borrados

        val colgantes = registro.cerrar().drop(emitidosDelActual)
        for (e in colgantes) observar(e)
        eventos += colgantes
        return colgantes
    }

    /**
     * Cierra el bloque y devuelve TODOS sus eventos, de todos los párrafos.
     *
     * Llamar dos veces devuelve lo mismo sin duplicar: el bloque puede cerrarse
     * por tiempo y volver a cerrarse desde el `onStop` que llega detrás.
     */
    fun cerrar(): List<EventoTecleoEntity> {
        if (congelado == null) {
            // El retrato se toma ANTES de plegar el registro vivo sobre los
            // acumulados; después, esas mismas pulsaciones estarían contadas en
            // los dos sitios. Las teclas colgantes que saca `cerrarRegistroActual`
            // no mueven ningún contador —`RegistroDeTecleo` no las cuenta como
            // pulsación— así que el retrato sigue siendo válido.
            val retrato = estado
            cerrarRegistroActual()
            congelado = retrato
        }
        return eventos.toList()
    }

    /** Los eventos acumulados hasta ahora, sin cerrar el bloque. */
    fun eventos(): List<EventoTecleoEntity> = eventos.toList()

    private fun observar(e: EventoTecleoEntity) {
        detectorPresion.observar(e.presion)
        detectorArea.observar(e.area)
    }

    /**
     * Pulsaciones por minuto del BLOQUE, con la convención de 5 caracteres = 1
     * palabra.
     *
     * Se mide con el reloj del último evento y no con «ahora», igual que en
     * [RegistroDeTecleo]: dos consultas seguidas sobre el mismo estado tienen que
     * dar el mismo número, o la cifra que acaba en la base dependería de cuándo
     * se leyó.
     */
    private fun ppm(caracteres: Int): Float {
        val ultimo = eventos.lastOrNull()?.tDownMs ?: return 0f
        val minutos = (ultimo - inicioMs) / 60_000f
        if (minutos <= 0f) return 0f
        return (caracteres / 5f) / minutos
    }
}
