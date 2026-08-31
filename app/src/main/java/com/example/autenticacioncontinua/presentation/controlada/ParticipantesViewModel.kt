package com.example.autenticacioncontinua.presentation.controlada

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autenticacioncontinua.data.controlada.IdentidadDelDispositivo
import com.example.autenticacioncontinua.domain.juego.Comprobacion
import com.example.autenticacioncontinua.domain.juego.ListaDeVerificacion
import com.example.autenticacioncontinua.domain.model.controlada.Participante
import com.example.autenticacioncontinua.domain.repository.IParticipanteRepository
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.repository.PlanDeSesion
import com.example.autenticacioncontinua.domain.repository.ResultadoAlta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lo que la pantalla de participantes necesita saber para pintarse. */
data class EstadoParticipantes(
    val participantes: List<Participante> = emptyList(),
    val filtro: String = "",
    val seleccionado: Participante? = null,
    val plan: PlanDeSesion? = null,
    val cargando: Boolean = false,
    /** Error de alta, para enseñarlo junto al campo. Se limpia al escribir. */
    val error: String? = null,
    /** Aviso no bloqueante: p. ej. el participante ya existía. */
    val aviso: String? = null,
    /**
     * Participante para el que se ha pedido el borrado, y cuántas sesiones se
     * llevaría por delante.
     *
     * El recuento va en el estado, y no lo calcula la pantalla, porque es lo que
     * hace que la confirmación diga algo: «se borrarán 3 sesiones» es una
     * decisión informada; «¿seguro?» no lo es.
     */
    val borrando: Participante? = null,
    val sesionesQueSeBorrarian: Int = 0,

    /** La etiqueta de ESTE terminal: `A`, `B` o `?` si nadie la asignó. */
    val etiquetaDispositivo: String = IdentidadDelDispositivo.SIN_ASIGNAR,

    /** Lista de verificación previa (P3). Se recalcula al abrirla. */
    val verificacion: List<Comprobacion> = emptyList(),
    val marcadas: Set<String> = emptySet(),
    /** `true` mientras se enseña la lista, antes de arrancar el minijuego. */
    val verificando: Boolean = false
) {
    /**
     * Si la lista de verificación deja empezar.
     *
     * Vive en el estado y no en la vista por lo mismo que [puedeIniciarSesion]:
     * es una regla del protocolo. Empezar una visita con el brillo cambiado o
     * sin etiqueta de terminal produce datos que parecen buenos y no lo son.
     */
    val puedeEmpezarLaVisita: Boolean
        get() = verificacion.isNotEmpty() &&
            ListaDeVerificacion.puedeEmpezar(verificacion, marcadas)

    val pendientesDeVerificacion: List<Comprobacion>
        get() = ListaDeVerificacion.pendientes(verificacion, marcadas)
    /**
     * La condición que la pantalla siguiente comprueba.
     *
     * Está aquí y no en la vista porque es una regla del protocolo, no de la
     * interfaz: una sesión sin participante produciría bloques y muestras
     * huérfanas, imposibles de asignar a nadie después.
     */
    val puedeIniciarSesion: Boolean get() = seleccionado != null

    /** La lista ya filtrada, que es lo que se pinta. */
    val visibles: List<Participante>
        get() = if (filtro.isBlank()) participantes
        else participantes.filter {
            it.seudonimo.contains(filtro.trim(), ignoreCase = true)
        }
}

/**
 * Alta, búsqueda y selección de participantes.
 *
 * EL FILTRADO ES EN MEMORIA, no una consulta. Con 20-30 participantes, ir a la
 * base en cada tecla del buscador es más lento y más código que filtrar una
 * lista que ya está cargada. Si algún día fueran miles, se cambia; hoy sería
 * complejidad sin motivo.
 *
 * LA SELECCIÓN NO SE PERSISTE A PROPÓSITO. Al arrancar la aplicación no hay
 * nadie seleccionado, y es deliberado: el investigador tiene que elegir
 * explícitamente a quién va a medir en cada visita. Si la selección
 * sobreviviera al cierre, una sesión podría empezar atribuida al participante
 * del día anterior — y eso no se detecta hasta analizar, cuando ya no hay
 * arreglo.
 */
class ParticipantesViewModel(
    private val participantes: IParticipanteRepository,
    private val sesiones: ISesionControladaRepository,
    /**
     * Identidad de ESTE terminal: la etiqueta `A`/`B` del protocolo.
     *
     * Se toma el objeto entero y no sólo la cadena porque la lista de
     * verificación permite ASIGNARLA cuando falta. Antes se leía una vez al
     * construir el ViewModel, así que asignarla no se veía hasta reiniciar.
     */
    private val identidad: IdentidadDelDispositivo,
    /** Porcentaje de batería, o `null` si no se puede leer. */
    private val bateria: () -> Float? = { null }
) : ViewModel() {

    private val dispositivoId: String get() = identidad.etiqueta

    private val _estado = MutableStateFlow(EstadoParticipantes())
    val estado: StateFlow<EstadoParticipantes> = _estado.asStateFlow()

    init { cargar() }

    fun cargar() {
        viewModelScope.launch {
            _estado.value = _estado.value.copy(cargando = true)
            val lista = participantes.todos()
            // Si el seleccionado sigue existiendo se refresca con sus datos
            // nuevos —el recuento de sesiones cambia al terminar una— en vez de
            // quedarse con una copia vieja que diría "3 sesiones" para siempre.
            val sel = _estado.value.seleccionado?.let { previo ->
                lista.firstOrNull { it.id == previo.id }
            }
            _estado.value = _estado.value.copy(
                participantes = lista,
                seleccionado = sel,
                cargando = false
            )
            if (sel != null) planificar(sel)
        }
    }

    fun filtrar(texto: String) {
        _estado.value = _estado.value.copy(filtro = texto)
    }

    fun seleccionar(p: Participante) {
        _estado.value = _estado.value.copy(seleccionado = p, plan = null)
        viewModelScope.launch { planificar(p) }
    }

    fun deseleccionar() {
        _estado.value = _estado.value.copy(seleccionado = null, plan = null)
    }

    /**
     * Da de alta y, si sale bien, deja al nuevo seleccionado.
     *
     * Encadenar las dos cosas es lo que espera quien está delante: se da de
     * alta a alguien porque va a hacer una sesión ahora mismo.
     */
    fun alta(seudonimo: String) {
        viewModelScope.launch {
            when (val r = participantes.alta(seudonimo)) {
                is ResultadoAlta.Creado -> {
                    _estado.value = _estado.value.copy(error = null, aviso = null)
                    cargar()
                    seleccionar(r.participante)
                }
                is ResultadoAlta.SeudonimoDuplicado -> {
                    // No es un fallo del programa sino de la mesa de trabajo: el
                    // investigador vuelve a dar de alta a alguien que ya vino.
                    // Se selecciona al existente y se avisa, que es lo que
                    // querría hacer a continuación.
                    _estado.value = _estado.value.copy(
                        error = null,
                        aviso = "${r.existente.seudonimo} ya estaba dado de alta; " +
                            "se ha seleccionado."
                    )
                    seleccionar(r.existente)
                }
                is ResultadoAlta.SeudonimoInvalido ->
                    _estado.value = _estado.value.copy(error = r.motivo, aviso = null)
            }
        }
    }

    fun limpiarMensajes() {
        _estado.value = _estado.value.copy(error = null, aviso = null)
    }

    // ------------------------------------------------------------------
    // Borrado
    // ------------------------------------------------------------------

    /**
     * Pide confirmación para borrar, contando antes lo que se va a perder.
     *
     * BORRAR ES PARA DESHACER UN ALTA EQUIVOCADA, no para limpiar lo que salió
     * mal: una sesión que salió mal se INVALIDA con su motivo y se conserva. El
     * borrado arrastra en cascada las sesiones, los bloques y los eventos de
     * tecleo del participante, así que la confirmación tiene que decir cuántos
     * son — si no, es un «¿seguro?» que nadie lee.
     */
    fun pedirBorrado(p: Participante) {
        viewModelScope.launch {
            val n = sesiones.sesionesDe(p.id).size
            _estado.value = _estado.value.copy(borrando = p, sesionesQueSeBorrarian = n)
        }
    }

    // ------------------------------------------------------------------
    // Lista de verificacion previa (P3)
    // ------------------------------------------------------------------

    /**
     * Abre la lista. Se RECALCULA cada vez, no se guarda.
     *
     * La bateria y la etiqueta pueden haber cambiado desde la visita anterior, y
     * una lista cacheada diria que la bateria estaba al 80% cuando lleva media
     * hora bajando.
     */
    fun abrirVerificacion() {
        _estado.value = _estado.value.copy(
            verificando = true,
            marcadas = emptySet(),
            etiquetaDispositivo = identidad.etiqueta,
            verificacion = ListaDeVerificacion.para(
                bateria = bateria(),
                etiquetaAsignada = !identidad.sinAsignar,
                hayParticipante = _estado.value.seleccionado != null
            )
        )
    }

    fun cerrarVerificacion() {
        _estado.value = _estado.value.copy(verificando = false, marcadas = emptySet())
    }

    /** Marca o desmarca una comprobacion manual. Las automaticas se ignoran. */
    fun alternarComprobacion(clave: String) {
        val e = _estado.value
        if (e.verificacion.any { it.clave == clave && it.automatica }) return
        val nuevas = if (clave in e.marcadas) e.marcadas - clave else e.marcadas + clave
        _estado.value = e.copy(marcadas = nuevas)
    }

    /**
     * Fija la etiqueta A/B de este terminal.
     *
     * Es del PROTOCOLO, no del aparato: si un terminal se rompe, el repuesto
     * hereda la etiqueta del que sustituye o la secuencia alternada de todos los
     * participantes se rompe a mitad del estudio. Se fija una vez y no se vuelve
     * a tocar; se recalcula la lista para que la comprobacion pase al momento.
     */
    fun asignarEtiqueta(etiqueta: String) {
        identidad.etiqueta = etiqueta
        abrirVerificacion()
        _estado.value.seleccionado?.let { p -> viewModelScope.launch { planificar(p) } }
    }

    fun cancelarBorrado() {
        _estado.value = _estado.value.copy(borrando = null, sesionesQueSeBorrarian = 0)
    }

    fun confirmarBorrado() {
        val p = _estado.value.borrando ?: return
        viewModelScope.launch {
            participantes.borrar(p.id)
            _estado.value = _estado.value.copy(
                borrando = null,
                sesionesQueSeBorrarian = 0,
                seleccionado = null,
                plan = null,
                aviso = "${p.seudonimo} y sus datos se han borrado."
            )
            cargar()
        }
    }

    /** Historial del participante seleccionado, para la pantalla de detalle. */
    suspend fun historial(participanteId: Long) = sesiones.sesionesDe(participanteId)

    private suspend fun planificar(p: Participante) {
        val plan = sesiones.planificar(p.id, dispositivoId)
        _estado.value = _estado.value.copy(plan = plan)
    }
}
