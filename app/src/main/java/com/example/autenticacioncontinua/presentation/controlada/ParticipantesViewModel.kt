package com.example.autenticacioncontinua.presentation.controlada

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val sesionesQueSeBorrarian: Int = 0
) {
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
    /** Identificador de ESTE terminal, para calcular el plan de la visita. */
    private val dispositivoId: String
) : ViewModel() {

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
