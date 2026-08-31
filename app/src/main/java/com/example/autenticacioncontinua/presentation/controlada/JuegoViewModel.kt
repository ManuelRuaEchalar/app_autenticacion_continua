package com.example.autenticacioncontinua.presentation.controlada

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.domain.juego.EstadoDeBloque
import com.example.autenticacioncontinua.domain.juego.FaseDeSesion
import com.example.autenticacioncontinua.domain.juego.GuionDeSesion
import com.example.autenticacioncontinua.domain.juego.MotorBloque
import com.example.autenticacioncontinua.domain.juego.RelojBloque
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Cómo quedó un bloque, para la pantalla de resumen. */
data class ResumenBloque(
    val indice: Int,
    val idioma: String,
    val pulsaciones: Int,
    val errores: Int,
    val borrados: Int,
    val ppm: Float,
    val precision: Float,
    val interrumpido: Boolean,
    val motivo: String
)

/** Todo lo que la pantalla del minijuego pinta. */
data class EstadoJuego(
    val fase: FaseDeSesion? = null,
    val sesionId: Long = 0L,
    val seudonimo: String = "",
    val visita: Int = 0,
    /** Idiomas de los tres bloques, para enseñarlos antes de empezar. */
    val idiomas: List<String> = emptyList(),
    val restanteMs: Long = 0L,
    val fraccion: Float = 0f,
    /** Nulo en aclimatación y en el resumen: sólo hay bloque mientras se teclea. */
    val bloque: EstadoDeBloque? = null,
    val resumen: List<ResumenBloque> = emptyList(),
    /** Se rellena al cerrar la sesión. */
    val estadoFinal: EstadoSesion? = null,
    val error: String? = null
) {
    val enBloque: Boolean get() = fase is FaseDeSesion.Bloque
    val terminada: Boolean get() = fase is FaseDeSesion.Fin
}

/**
 * Conduce una visita completa: aclimatación, tres bloques y cierre.
 *
 * ### Por qué la sesión es un bucle y no una máquina de estados con eventos
 *
 * Porque el guion está decidido de antemano (ver [GuionDeSesion]) y lo único
 * que hace avanzar la sesión es el tiempo. Un bucle que recorre las fases y
 * espera a que cada cronómetro se agote dice literalmente eso, y deja el orden
 * de las operaciones —abrir bloque, teclear, cerrar bloque— visible en un solo
 * sitio. Una máquina de transiciones repartiría lo mismo en cinco métodos y
 * haría más fácil, no más difícil, colarse un caso en el que un bloque se abre y
 * no se cierra.
 *
 * ### Qué se persiste y qué no
 *
 * La aclimatación **no genera ninguna fila**: ni bloque, ni eventos. Es la fase
 * en la que se teclea sin que cuente, y está para que las primeras pulsaciones
 * con un teclado desconocido no entren en el corpus.
 *
 * Cada bloque se persiste ENTERO al cerrarse, no fila a fila mientras se teclea.
 * A un ritmo normal un bloque de cien segundos deja unos quinientos eventos:
 * cabe de sobra en memoria, y escribir al final evita meter `fsync` en el camino
 * crítico del tecleo, que es justo la señal que se está midiendo.
 *
 * ### La interrupción aborta la visita, no sólo el bloque
 *
 * Si el participante recibe una llamada o la aplicación pasa a segundo plano, no
 * tiene sentido seguir al bloque siguiente: nadie está tecleando. Se marca el
 * bloque en curso como interrumpido con su motivo, se cierra la sesión como
 * ABORTADA y se para. Los bloques ya completos se conservan y son utilizables:
 * es exactamente la semántica de [EstadoSesion.ABORTADA].
 *
 * El tiempo **no se falsea** para que cuadre. Un bloque de 40 s marcado como
 * interrumpido es analizable; uno de 40 s presentado como de 100 s corrompe en
 * silencio cualquier tasa por unidad de tiempo.
 *
 * ### Relojes inyectados
 *
 * [ahora] y [semillaDe] son parámetros para que la visita entera se pueda
 * simular en la JVM con el reloj virtual de `runTest`, en milisegundos y sin
 * `Thread.sleep`. Es el mismo patrón de `MonitorBloque` y `ProtocoloDeBloques`.
 */
class JuegoViewModel(
    private val sesiones: ISesionControladaRepository,
    private val selector: SelectorDeParrafos,
    private val ahora: () -> Long = { System.currentTimeMillis() },
    private val bateria: () -> Float? = { null },
    private val semillaDe: () -> Long = { Random.nextLong() }
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoJuego())
    val estado: StateFlow<EstadoJuego> = _estado.asStateFlow()

    private var trabajo: Job? = null

    private var sesionId = 0L
    private var participanteId = 0L
    private var semilla = 0L
    private var vistos: Set<String> = emptySet()

    private var reloj: RelojBloque? = null
    private var motor: MotorBloque? = null
    private var bloqueId = 0L
    private var abortada = false

    private val resumen = mutableListOf<ResumenBloque>()

    /**
     * Abre la sesión y arranca la visita.
     *
     * MIENTRAS HAY UNA VISITA EN CURSO NO HACE NADA. La pantalla se recompone
     * varias veces por segundo y una segunda llamada abriría una segunda fila en
     * `sesiones_controladas` para la misma visita.
     *
     * PERO SÍ ARRANCA UNA VISITA NUEVA cuando la anterior terminó, y hace falta:
     * el ViewModel vive lo que viva la Activity, y en una tarde de campo se
     * miden varios participantes seguidos sin cerrar la aplicación. Con un
     * cerrojo permanente, del segundo participante en adelante la pantalla se
     * quedaría con el resumen del primero.
     */
    fun iniciar(participanteId: Long, dispositivoReal: String) {
        if (trabajo?.isActive == true) return
        reiniciar()
        this.participanteId = participanteId
        trabajo = viewModelScope.launch { visita(participanteId, dispositivoReal) }
    }

    /** Deja el ViewModel como recién creado, para la visita siguiente. */
    private fun reiniciar() {
        trabajo = null
        sesionId = 0L
        semilla = 0L
        vistos = emptySet()
        reloj = null
        motor = null
        bloqueId = 0L
        abortada = false
        resumen.clear()
        _estado.value = EstadoJuego()
    }

    private suspend fun visita(participanteId: Long, dispositivoReal: String) {
        val plan = sesiones.planificar(participanteId, dispositivoReal)
        if (plan == null) {
            _estado.value = EstadoJuego(error = "no existe el participante $participanteId")
            return
        }

        semilla = semillaDe()
        sesionId = sesiones.abrir(participanteId, dispositivoReal, semilla, bateria())
        // Los párrafos ya vistos se leen UNA vez, al abrir: durante la visita no
        // cambian, y consultarlos en cada bloque tocaría la base entre bloques
        // para obtener lo mismo.
        vistos = sesiones.parrafosVistosPor(participanteId)

        val guion = GuionDeSesion.fases(plan.visita, selector)
        _estado.value = EstadoJuego(
            sesionId = sesionId,
            seudonimo = plan.seudonimo,
            visita = plan.visita,
            idiomas = selector.idiomasDeSesion(plan.visita)
        )

        for (fase in guion) {
            if (fase is FaseDeSesion.Fin) break
            entrar(fase)
            val r = reloj!!
            while (!r.terminado) {
                delay(TIC_MS)
                publicar(fase)
            }
            salir(fase)
            if (abortada) break
        }

        cerrar()
    }

    private suspend fun entrar(fase: FaseDeSesion) {
        reloj = RelojBloque(fase.duracionMs, ahora)

        if (fase is FaseDeSesion.Bloque) {
            bloqueId = sesiones.abrirBloque(sesionId, fase.indice, fase.idioma)
            val parrafos = selector.parrafosPara(
                idioma = fase.idioma,
                semillaSesion = semilla,
                indiceBloque = fase.indice,
                yaVistos = vistos
            )
            // El reloj arranca DESPUÉS de elegir los párrafos: cargarlos podría
            // costar unos milisegundos y se los comería el tiempo del bloque.
            reloj!!.iniciar()
            motor = MotorBloque(
                bloqueId = bloqueId,
                idioma = fase.idioma,
                parrafos = parrafos,
                inicioMs = reloj!!.inicioMs
            )
        } else {
            motor = null
            reloj!!.iniciar()
        }
        publicar(fase)
    }

    private suspend fun salir(fase: FaseDeSesion) {
        val r = reloj ?: return
        if (fase !is FaseDeSesion.Bloque) {
            // La aclimatación no deja rastro en la base. Es su razón de ser.
            return
        }

        val m = motor ?: return
        val eventos = m.cerrar()
        val e = m.estado
        sesiones.guardarEventos(eventos)
        sesiones.cerrarBloque(
            bloqueId = bloqueId,
            pulsaciones = e.pulsaciones,
            errores = e.errores,
            borrados = e.borrados,
            ppm = e.ppm,
            precision = e.precision,
            parrafosUsados = m.parrafosUsados(),
            interrumpido = r.interrumpido,
            motivoInterrupcion = r.motivoInterrupcion
        )

        // Los párrafos de este bloque pasan a vistos para que el siguiente de la
        // misma visita no los repita.
        vistos = vistos + m.parrafosUsados()

        resumen += ResumenBloque(
            indice = fase.indice,
            idioma = fase.idioma,
            pulsaciones = e.pulsaciones,
            errores = e.errores,
            borrados = e.borrados,
            ppm = e.ppm,
            precision = e.precision,
            interrumpido = r.interrumpido,
            motivo = r.motivoInterrupcion
        )
        motor = null
    }

    private suspend fun cerrar() {
        // ABORTADA si algo cortó la visita; COMPLETA sólo si los tres bloques
        // llegaron al final. No se invalida: una visita interrumpida sigue
        // teniendo bloques utilizables, y INVALIDADA se reserva para lo que el
        // investigador declara malo (participante equivocado, teclado sin
        // configurar), que es una decisión humana y no del programa.
        val estadoFinal =
            if (abortada || resumen.any { it.interrumpido }) EstadoSesion.ABORTADA
            else EstadoSesion.COMPLETA
        sesiones.cerrar(sesionId, estadoFinal, bateria())

        _estado.value = _estado.value.copy(
            fase = FaseDeSesion.Fin,
            bloque = null,
            restanteMs = 0L,
            fraccion = 1f,
            resumen = resumen.toList(),
            estadoFinal = estadoFinal
        )
    }

    /**
     * Una pulsación del teclado en pantalla.
     *
     * Se ignora fuera de un bloque —en aclimatación se teclea y no cuenta— y con
     * el cronómetro agotado: entre que el reloj llega a cero y el bucle se
     * entera pasa como mucho un tic, y una tecla que caiga ahí pertenecería a un
     * bloque ya cerrado.
     */
    fun onPulsacion(p: PulsacionCruda) {
        val m = motor ?: return
        if (reloj?.terminado != false) return
        m.aceptar(p)
        _estado.value = _estado.value.copy(bloque = m.estado)
    }

    /**
     * Algo cortó la visita: llamada entrante, pantalla apagada, aplicación al
     * fondo.
     *
     * Marca el bloque en curso y aborta. No hace nada si la visita ya terminó o
     * si aún no había empezado, para que un `onStop` de más —al salir de la
     * pantalla de resumen, por ejemplo— no reabra nada.
     */
    fun onPausa(motivo: String) {
        val r = reloj ?: return
        if (r.terminado || _estado.value.terminada) return
        abortada = true
        r.interrumpir(motivo)
    }

    private fun publicar(fase: FaseDeSesion) {
        val r = reloj ?: return
        _estado.value = _estado.value.copy(
            fase = fase,
            restanteMs = r.restanteMs,
            fraccion = r.fraccion,
            bloque = motor?.estado
        )
    }

    companion object {
        /**
         * Cada cuánto se refresca la cuenta atrás.
         *
         * 100 ms: la cifra de segundos cambia sin saltos visibles y el coste es
         * despreciable. El corte del bloque NO depende de este valor —lo decide
         * [RelojBloque] con marcas de tiempo— así que un tic perdido retrasa el
         * cierre unas décimas pero no altera la duración registrada.
         */
        const val TIC_MS = 100L
    }
}
