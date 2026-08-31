package com.example.autenticacioncontinua.controlada

import com.example.autenticacioncontinua.data.local.dao.controlada.RepartoDispositivo
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.CovariableSesionEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity
import com.example.autenticacioncontinua.domain.model.controlada.Participante
import com.example.autenticacioncontinua.domain.model.controlada.PlanDeDispositivos
import com.example.autenticacioncontinua.domain.repository.IParticipanteRepository
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.repository.PlanDeSesion
import com.example.autenticacioncontinua.domain.repository.ResultadoAlta

/**
 * Dobles en memoria de los dos repositorios del corpus controlado.
 *
 * Reproducen las reglas que de verdad importan —unicidad del seudónimo,
 * normalización, recuento de sesiones utilizables— porque son justo las que el
 * ViewModel tiene que respetar. Un doble que aceptara cualquier alta dejaría
 * pasar pruebas que en el aparato fallarían.
 */
class ParticipantesEnMemoria : IParticipanteRepository {

    private val filas = mutableListOf<Participante>()
    private var siguienteId = 1L

    /** Sesiones utilizables por participante, para el recuento. */
    val sesionesPorParticipante = mutableMapOf<Long, Int>()

    override suspend fun alta(seudonimo: String): ResultadoAlta {
        val canonico = ParticipanteEntity.normalizar(seudonimo)
        if (canonico.isEmpty() || !PATRON.matches(canonico)) {
            return ResultadoAlta.SeudonimoInvalido(
                "usa de 2 a 16 caracteres, solo letras, digitos, guion y guion bajo"
            )
        }
        filas.firstOrNull { it.seudonimo == canonico }?.let {
            return ResultadoAlta.SeudonimoDuplicado(it)
        }
        val p = Participante(id = siguienteId++, seudonimo = canonico, fechaAltaMs = 0L)
        filas += p
        return ResultadoAlta.Creado(p)
    }

    override suspend fun todos(): List<Participante> =
        filas.map { it.copy(sesionesHechas = sesionesPorParticipante[it.id] ?: 0) }
            .sortedBy { it.seudonimo }

    override suspend fun porId(id: Long): Participante? = todos().firstOrNull { it.id == id }

    override suspend fun porSeudonimo(seudonimo: String): Participante? =
        todos().firstOrNull { it.seudonimo == ParticipanteEntity.normalizar(seudonimo) }

    override suspend fun buscar(texto: String): List<Participante> =
        todos().filter { it.seudonimo.contains(ParticipanteEntity.normalizar(texto)) }

    override suspend fun borrar(id: Long) {
        filas.removeAll { it.id == id }
        sesionesPorParticipante.remove(id)
    }

    private companion object {
        val PATRON = Regex("^[A-Z0-9_-]{2,16}$")
    }
}

/** Doble del repositorio de sesiones. Sólo implementa lo que el ViewModel usa. */
class SesionesEnMemoria : ISesionControladaRepository {

    val sesiones = mutableListOf<SesionControladaEntity>()
    private var siguienteId = 1L

    override suspend fun planificar(participanteId: Long, dispositivoReal: String): PlanDeSesion? {
        val seudonimo = seudonimoDe[participanteId] ?: return null
        val visita = sesiones.count { it.participanteId == participanteId } + 1
        return PlanDeSesion(
            participanteId = participanteId,
            seudonimo = seudonimo,
            visita = visita,
            dispositivoEsperado = PlanDeDispositivos.dispositivoEsperado(seudonimo, visita),
            dispositivoReal = dispositivoReal,
            repartoActual = sesiones.filter { it.participanteId == participanteId }
                .groupingBy { it.dispositivoId }.eachCount()
                .map { RepartoDispositivo(it.key, it.value) }
        )
    }

    /** El doble necesita saber el seudónimo para calcular el plan. */
    val seudonimoDe = mutableMapOf<Long, String>()

    override suspend fun abrir(
        participanteId: Long,
        dispositivoReal: String,
        semilla: Long,
        bateriaInicio: Float?
    ): Long {
        val id = siguienteId++
        sesiones += SesionControladaEntity(
            id = id,
            participanteId = participanteId,
            dispositivoId = dispositivoReal,
            inicioMs = 0L,
            ordenDispositivo = sesiones.count { it.participanteId == participanteId } + 1,
            semillaSeleccion = semilla,
            versionApp = "test",
            versionProtocolo = "1.0",
            bateriaInicio = bateriaInicio
        )
        return id
    }

    override suspend fun cerrar(sesionId: Long, estado: EstadoSesion, bateriaFin: Float?) {
        val i = sesiones.indexOfFirst { it.id == sesionId }
        if (i >= 0) sesiones[i] = sesiones[i].copy(estado = estado.name, finMs = 1L)
    }

    override suspend fun invalidar(sesionId: Long, motivo: String) {
        require(motivo.isNotBlank())
        val i = sesiones.indexOfFirst { it.id == sesionId }
        if (i >= 0) sesiones[i] = sesiones[i].copy(
            estado = EstadoSesion.INVALIDADA.name, motivoInvalidacion = motivo
        )
    }

    override suspend fun cerrarHuerfanas(): Int {
        val abiertas = sesiones.filter { it.estaAbierta }
        for (s in abiertas) cerrar(s.id, EstadoSesion.ABORTADA, null)
        return abiertas.size
    }

    override suspend fun sesion(sesionId: Long) = sesiones.firstOrNull { it.id == sesionId }

    override suspend fun sesionesDe(participanteId: Long) =
        sesiones.filter { it.participanteId == participanteId }.sortedByDescending { it.inicioMs }

    // --- bloques y eventos -------------------------------------------
    //
    // Se guardan de verdad, no se tiran. Las pruebas del minijuego comprueban
    // que la aclimatación NO deja fila y que cada bloque queda con su idioma y
    // su marca de interrumpido; con un doble que ignorara las escrituras, esas
    // pruebas pasarían con cualquier implementación.

    val bloques = mutableListOf<BloqueEntity>()
    val eventos = mutableListOf<EventoTecleoEntity>()
    private var siguienteBloque = 1L

    override suspend fun abrirBloque(sesionId: Long, indice: Int, idioma: String): Long {
        require(idioma == BloqueEntity.IDIOMA_ESPANOL || idioma == BloqueEntity.IDIOMA_LATIN) {
            "idioma '$idioma' desconocido"
        }
        val id = siguienteBloque++
        bloques += BloqueEntity(
            id = id, sesionId = sesionId, indice = indice, inicioMs = 0L, idioma = idioma
        )
        return id
    }

    override suspend fun cerrarBloque(
        bloqueId: Long, pulsaciones: Int, errores: Int, borrados: Int,
        ppm: Float, precision: Float, parrafosUsados: List<String>,
        interrumpido: Boolean, motivoInterrupcion: String
    ) {
        val i = bloques.indexOfFirst { it.id == bloqueId }
        if (i < 0) return
        bloques[i] = bloques[i].copy(
            finMs = 1L,
            pulsaciones = pulsaciones,
            errores = errores,
            borrados = borrados,
            ppm = ppm,
            precision = precision,
            parrafosUsados = parrafosUsados.joinToString(","),
            interrumpido = interrumpido,
            motivoInterrupcion = motivoInterrupcion
        )
    }

    override suspend fun bloquesDe(sesionId: Long): List<BloqueEntity> =
        bloques.filter { it.sesionId == sesionId }.sortedBy { it.indice }

    override suspend fun parrafosVistosPor(participanteId: Long): Set<String> {
        val suyas = sesiones.filter { it.participanteId == participanteId }.map { it.id }.toSet()
        return bloques.filter { it.sesionId in suyas }
            .flatMap { it.parrafosUsados.split(',') }
            .mapNotNull { it.trim().ifEmpty { null } }
            .toSet()
    }

    override suspend fun guardarEventos(eventos: List<EventoTecleoEntity>) {
        this.eventos += eventos
    }

    override suspend fun eventosDe(bloqueId: Long): List<EventoTecleoEntity> =
        eventos.filter { it.bloqueId == bloqueId }

    override suspend fun guardarMuestras(muestras: List<MuestraInercialEntity>) = Unit
    override suspend fun guardarCovariables(filas: List<CovariableSesionEntity>) = Unit
    override suspend fun muestrasDe(bloqueId: Long): List<MuestraInercialEntity> = emptyList()
    override suspend fun tasaEfectivaHz(bloqueId: Long): Double? = null
    override suspend fun cuantasMuestras(bloqueId: Long) = 0
}
