package com.example.autenticacioncontinua.presentation.controlada

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.sensor.CapturaInercial
import com.example.autenticacioncontinua.domain.juego.EstadoDeBloque
import com.example.autenticacioncontinua.domain.juego.FaseDeSesion
import com.example.autenticacioncontinua.domain.juego.GuionDeSesion
import com.example.autenticacioncontinua.domain.juego.MotorBloque
import com.example.autenticacioncontinua.domain.juego.RelojBloque
import com.example.autenticacioncontinua.domain.export.IExportadorDeSesion
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.session.ISessionManager
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val motivo: String,
    /**
     * Filas escritas en `muestras_inerciales`. A 100 Hz un bloque de 100 s
     * deberían ser ~10 000; muchas menos significa que el terminal no entregó
     * la tasa pedida, y eso hay que verlo en el momento, no al analizar.
     */
    val muestrasInerciales: Long = 0L,
    /** Muestras que la cola descartó por escritura lenta. Debería ser 0. */
    val perdidasEnCola: Long = 0L,
    val magnetometro: Boolean = false
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
    val error: String? = null,
    /**
     * Cómo va el guardado del paquete de la visita (R5, fase 9).
     *
     * VIVE EN EL ESTADO Y NO EN LA VISTA porque de él depende si se puede salir
     * de la pantalla, y eso es una regla del protocolo, no de la interfaz: una
     * visita que se cierra sin exportar deja su única copia dentro del teléfono.
     */
    val exportacion: EstadoExportacion = EstadoExportacion.Pendiente
) {
    val enBloque: Boolean get() = fase is FaseDeSesion.Bloque
    val terminada: Boolean get() = fase is FaseDeSesion.Fin

    /** R5: no se sale de la visita hasta que el paquete está escrito y verificado. */
    val puedeSalir: Boolean get() = exportacion is EstadoExportacion.Hecha
}

/** Las cuatro situaciones del guardado, que la pantalla tiene que saber distinguir. */
sealed interface EstadoExportacion {
    data object Pendiente : EstadoExportacion
    data object EnCurso : EstadoExportacion

    /**
     * Escrito y RELEÍDO. No basta con haberlo escrito: el paquete se vuelve a
     * abrir y se comprueban las huellas de sus cuatro tablas antes de dar la
     * visita por guardada. Escribir un fichero corrupto y decir que todo fue
     * bien es exactamente el fallo que esta fase existe para evitar.
     */
    data class Hecha(
        val nombre: String,
        val huellaCorta: String,
        val kb: Long,
        val filas: Map<String, Int>
    ) : EstadoExportacion

    data class Fallida(val motivo: String) : EstadoExportacion
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
    /**
     * Captura de los tres sensores durante cada bloque.
     *
     * Se pide como fábrica y no como instancia: `CapturaInercial` guarda el
     * estado de UNA captura y `iniciar` revienta si ya hay otra en curso. Una
     * por bloque deja además que el bloque siguiente empiece con los contadores
     * de pérdidas y descartes a cero, que es lo que se reporta por bloque.
     */
    private val capturaDe: () -> CapturaInercial,
    /**
     * Recolección ambiental. Se suspende mientras dura la visita (R1).
     *
     * Es la ÚNICA interacción permitida entre este módulo y la recogida
     * ambiental, y va aquí y no en la pantalla porque es una regla del
     * protocolo: si dependiera de que alguien la invoque desde la interfaz,
     * bastaría un camino de navegación nuevo para saltársela.
     */
    private val ambiental: ISessionManager,
    /**
     * Tramos etiquetados. Se anota la visita entera como NO del dueño.
     *
     * Son los TIRANTES del cinturón anterior: si la suspensión fallara y
     * entraran muestras ambientales durante la visita, `ExclusionEtiquetada`
     * las descarta igualmente del entrenamiento del dueño. Dos mecanismos
     * independientes para el mismo fallo, que es silencioso y caro.
     */
    private val tramos: ILabeledSessionRepository,
    /** Paquete de la visita (R5). Ver [exportar]. */
    private val exportador: IExportadorDeSesion,
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
    private var captura: CapturaInercial? = null
    private var tramoId = 0L
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
        captura = null
        tramoId = 0L
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

        // EL ORDEN IMPORTA, y es el mismo que ya usa `startLabeledCapture`:
        // primero se protege el corpus ambiental y sólo después se abre nada.
        // Al revés, entre abrir la sesión y suspender cabe una ráfaga que
        // entraría como uso del dueño.
        ambiental.suspender()
        tramoId = tramos.abrir(
            participantId = plan.seudonimo,
            // NO es el dueño: es la marca que hace que `ExclusionEtiquetada`
            // descarte del entrenamiento cualquier muestra ambiental que se
            // hubiera colado durante la visita.
            isOwner = false,
            note = "sesion controlada, visita ${plan.visita}"
        )

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

        try {
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
        } finally {
            // Pase lo que pase. Ver la nota de `devolverElTelefono`.
            withContext(NonCancellable) { devolverElTelefono() }
        }
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
            // Los sensores arrancan ANTES que el cronómetro: encenderlos tarda
            // unas decenas de milisegundos y hacerlo después dejaría el
            // principio del bloque sin muestras inerciales, justo donde está la
            // primera pulsación.
            captura = capturaDe().also { it.iniciar(bloqueId) }

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

        // Los sensores se paran ANTES de escribir el bloque: `detener` vacía lo
        // que quede en la cola, y hacerlo después dejaría muestras entrando en
        // un bloque ya cerrado.
        val resumenCaptura = captura?.detener()
        captura = null

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
            motivo = r.motivoInterrupcion,
            muestrasInerciales = resumenCaptura?.filasEscritas ?: 0L,
            perdidasEnCola = resumenCaptura?.perdidasEnCola ?: 0L,
            magnetometro = resumenCaptura?.magnetometroDisponible ?: false
        )
        motor = null
    }

    /**
     * Devuelve el teléfono a su estado normal.
     *
     * VA EN UN `finally`: si la visita revienta a mitad —una excepción al
     * escribir, la app que se va al fondo— la recolección ambiental tiene que
     * volver igualmente. Dejarla suspendida sería peor que no haberla
     * suspendido: el dueño dejaría de recoger datos indefinidamente y sin
     * ningún aviso, porque la suspensión no sobrevive a un reinicio del proceso
     * pero sí a que se cierre la pantalla del estudio.
     *
     * Los sensores se paran aquí también por si el fallo cayó dentro de un
     * bloque: un `IFuenteSensor` sin detener sigue consumiendo bateria, que es
     * variable dependiente del estudio.
     */
    private suspend fun devolverElTelefono() {
        runCatching { captura?.detener() }
        captura = null
        if (tramoId > 0L) {
            runCatching { tramos.cerrar(tramoId, ahora()) }
            tramoId = 0L
        }
        ambiental.reanudar()
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

        // La exportación va DESPUÉS de cerrar y no dentro del cierre: si
        // fallara, la sesión ya está cerrada y sus datos a salvo en la base. Al
        // revés —exportar y luego cerrar— un fallo de disco dejaría la sesión
        // abierta y la siguiente visita la heredaría.
        exportar()
    }

    /**
     * Escribe el paquete de la visita y lo vuelve a leer para comprobarlo.
     *
     * SE LANZA SOLO al terminar, sin que nadie pulse nada. R5 pide que exportar
     * sea obligatorio, y la forma de que algo obligatorio se cumpla siempre es
     * que no dependa de que alguien se acuerde: al llegar a la pantalla de
     * resumen el guardado ya está en marcha, y el botón de salir no se habilita
     * hasta que termina.
     *
     * ES REINTENTABLE. Un fallo aquí no pierde nada —los datos siguen en la
     * base— pero deja la visita sin copia, así que la pantalla ofrece repetir.
     */
    fun exportar() {
        if (sesionId == 0L) return
        if (_estado.value.exportacion is EstadoExportacion.EnCurso) return
        _estado.value = _estado.value.copy(exportacion = EstadoExportacion.EnCurso)
        viewModelScope.launch {
            val resultado = exportador.exportar(sesionId).mapCatching { paquete ->
                // Releer lo escrito. Es la mitad de la prueba que pide la fase 9
                // y lo que separa "se escribió un fichero" de "hay una copia".
                val v = exportador.verificar(paquete.fichero).getOrThrow()
                check(v.integro) {
                    "el paquete se escribió pero no se relee bien: " +
                        "${v.tablasCorruptas.joinToString()} corrupta(s)"
                }
                check(v.huella == paquete.huella) {
                    "la huella del fichero cambió entre escribirlo y releerlo"
                }
                paquete
            }
            _estado.value = _estado.value.copy(
                exportacion = resultado.fold(
                    onSuccess = {
                        EstadoExportacion.Hecha(
                            nombre = it.fichero.name,
                            huellaCorta = it.huellaCorta,
                            kb = it.bytes / 1024,
                            filas = it.filasPorTabla
                        )
                    },
                    onFailure = { EstadoExportacion.Fallida(it.message ?: "error desconocido") }
                )
            )
        }
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
