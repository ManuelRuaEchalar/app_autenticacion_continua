package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.data.local.dao.controlada.RepartoDispositivo
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.CovariableSesionEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity

/** Lo que hay que saber antes de abrir una sesión. */
data class PlanDeSesion(
    val participanteId: Long,
    val seudonimo: String,
    /** Número de esta visita, empezando en 1. */
    val visita: Int,
    val dispositivoEsperado: String,
    val dispositivoReal: String,
    val repartoActual: List<RepartoDispositivo>
) {
    /** El terminal que hay delante no es el que tocaba. Se avisa, no se bloquea. */
    val dispositivoNoEsElEsperado: Boolean get() = dispositivoEsperado != dispositivoReal
}

/**
 * Escritura y consulta del corpus de sesiones controladas.
 *
 * LAS TABLAS DE GRAN VOLUMEN NO PASAN POR UN MODELO DE DOMINIO. Las muestras
 * inerciales y los eventos de tecleo se aceptan y se devuelven como entidades:
 * a 100 Hz son 90 000 filas por sesión, y mapearlas a un segundo tipo
 * asignaría memoria en el camino crítico de la captura para que nadie lea el
 * resultado. Participantes y sesiones, que la interfaz sí manipula, sí tienen
 * modelo de dominio.
 */
interface ISesionControladaRepository {

    /** Qué visita toca y en qué terminal, sin abrir nada todavía. */
    suspend fun planificar(participanteId: Long, dispositivoReal: String): PlanDeSesion?

    /**
     * Abre una sesión y devuelve su identificador.
     *
     * [semilla] fija la selección de párrafos e idiomas. Se guarda para poder
     * reconstruir meses después la secuencia exacta de textos que vio el
     * participante sin haberla almacenado entera.
     */
    suspend fun abrir(
        participanteId: Long,
        dispositivoReal: String,
        semilla: Long,
        bateriaInicio: Float?
    ): Long

    suspend fun cerrar(
        sesionId: Long,
        estado: EstadoSesion,
        bateriaFin: Float?
    )

    /** Marca una sesión como inválida con su motivo. No borra nada. */
    suspend fun invalidar(sesionId: Long, motivo: String)

    /**
     * Cierra como ABORTADAS las sesiones que quedaron abiertas.
     *
     * Se llama al arrancar la aplicación. Si no, la siguiente visita del mismo
     * participante parecería la continuación de la anterior y los bloques de
     * dos días distintos acabarían en el mismo episodio, que es justo lo que la
     * separación por sesiones existe para evitar.
     */
    suspend fun cerrarHuerfanas(): Int

    suspend fun sesion(sesionId: Long): SesionControladaEntity?
    suspend fun sesionesDe(participanteId: Long): List<SesionControladaEntity>

    // --- bloques -------------------------------------------------------

    suspend fun abrirBloque(sesionId: Long, indice: Int, idioma: String): Long

    suspend fun cerrarBloque(
        bloqueId: Long,
        pulsaciones: Int,
        errores: Int,
        borrados: Int,
        ppm: Float,
        precision: Float,
        parrafosUsados: List<String>,
        interrumpido: Boolean = false,
        motivoInterrupcion: String = ""
    )

    suspend fun bloquesDe(sesionId: Long): List<BloqueEntity>

    /**
     * Párrafos que el participante ya ha transcrito en cualquiera de sus visitas.
     *
     * Lo consume `SelectorDeParrafos.parrafosPara` para no repetirle un texto.
     * La regla es POR PARTICIPANTE, nunca global: dos personas distintas sí
     * pueden —y conviene que lo hagan— transcribir el mismo texto, o la
     * dificultad del material quedaría confundida con la persona.
     */
    suspend fun parrafosVistosPor(participanteId: Long): Set<String>

    // --- datos ---------------------------------------------------------

    suspend fun guardarMuestras(muestras: List<MuestraInercialEntity>)
    suspend fun guardarEventos(eventos: List<EventoTecleoEntity>)
    suspend fun guardarCovariables(filas: List<CovariableSesionEntity>)

    suspend fun muestrasDe(bloqueId: Long): List<MuestraInercialEntity>
    suspend fun eventosDe(bloqueId: Long): List<EventoTecleoEntity>

    /** Tasa efectiva de muestreo del bloque, en Hz, o null si no hay muestras. */
    suspend fun tasaEfectivaHz(bloqueId: Long): Double?

    suspend fun cuantasMuestras(bloqueId: Long): Int
}
