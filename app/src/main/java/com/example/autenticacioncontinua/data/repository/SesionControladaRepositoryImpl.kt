package com.example.autenticacioncontinua.data.repository

import android.util.Log
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.CovariableSesionEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity
import com.example.autenticacioncontinua.domain.model.controlada.PlanDeDispositivos
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.repository.PlanDeSesion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SesionControladaRepositoryImpl(
    private val db: AppDatabase,
    private val versionApp: String,
    private val versionProtocolo: String
) : ISesionControladaRepository {

    override suspend fun planificar(
        participanteId: Long,
        dispositivoReal: String
    ): PlanDeSesion? = withContext(Dispatchers.IO) {
        val participante = db.participanteDao().porId(participanteId) ?: return@withContext null
        val visita = db.sesionControladaDao().cuantasDe(participanteId) + 1
        PlanDeSesion(
            participanteId = participanteId,
            seudonimo = participante.seudonimo,
            visita = visita,
            dispositivoEsperado = PlanDeDispositivos
                .dispositivoEsperado(participante.seudonimo, visita),
            dispositivoReal = dispositivoReal,
            repartoActual = db.sesionControladaDao().repartoPorDispositivo(participanteId)
        )
    }

    override suspend fun abrir(
        participanteId: Long,
        dispositivoReal: String,
        semilla: Long,
        bateriaInicio: Float?
    ): Long = withContext(Dispatchers.IO) {
        // `ordenDispositivo` se calcula aquí y no lo pasa quien llama: es el
        // número de visita, y dejarlo en manos de la interfaz permitiría abrir
        // dos sesiones con el mismo número si se pulsa dos veces.
        val visita = db.sesionControladaDao().cuantasDe(participanteId) + 1
        db.sesionControladaDao().insertar(
            SesionControladaEntity(
                participanteId = participanteId,
                dispositivoId = dispositivoReal,
                inicioMs = System.currentTimeMillis(),
                ordenDispositivo = visita,
                semillaSeleccion = semilla,
                versionApp = versionApp,
                versionProtocolo = versionProtocolo,
                bateriaInicio = bateriaInicio,
                estado = EstadoSesion.EN_CURSO.name
            )
        )
    }

    override suspend fun cerrar(
        sesionId: Long,
        estado: EstadoSesion,
        bateriaFin: Float?
    ) = withContext(Dispatchers.IO) {
        db.sesionControladaDao()
            .cerrar(sesionId, System.currentTimeMillis(), estado.name, bateriaFin)
    }

    override suspend fun invalidar(sesionId: Long, motivo: String) = withContext(Dispatchers.IO) {
        require(motivo.isNotBlank()) {
            "invalidar sin motivo. El motivo es el dato: una sesion invalidada " +
                "sin explicacion es indistinguible de una borrada."
        }
        db.sesionControladaDao().marcar(sesionId, EstadoSesion.INVALIDADA.name, motivo.trim())
    }

    override suspend fun cerrarHuerfanas(): Int = withContext(Dispatchers.IO) {
        val abiertas = db.sesionControladaDao().abiertas()
        for (s in abiertas) {
            // El fin se pone en el inicio del último bloque cerrado, o en el de
            // la sesión si no llegó a haber ninguno. Poner `ahora` fabricaría
            // una sesión de veinte horas si la app estuvo cerrada toda la noche,
            // y esa duración acabaría en cualquier tasa por unidad de tiempo.
            val bloques = db.bloqueDao().de(s.id)
            val fin = bloques.maxOfOrNull { if (it.finMs > 0) it.finMs else it.inicioMs }
                ?: s.inicioMs
            db.sesionControladaDao()
                .cerrar(s.id, fin, EstadoSesion.ABORTADA.name, s.bateriaFin)
        }
        if (abiertas.isNotEmpty()) {
            Log.w(TAG, "cerradas ${abiertas.size} sesiones que quedaron abiertas")
        }
        abiertas.size
    }

    override suspend fun sesion(sesionId: Long): SesionControladaEntity? =
        withContext(Dispatchers.IO) { db.sesionControladaDao().porId(sesionId) }

    override suspend fun sesionesDe(participanteId: Long): List<SesionControladaEntity> =
        withContext(Dispatchers.IO) { db.sesionControladaDao().de(participanteId) }

    // --- bloques -------------------------------------------------------

    override suspend fun abrirBloque(sesionId: Long, indice: Int, idioma: String): Long =
        withContext(Dispatchers.IO) {
            require(idioma == BloqueEntity.IDIOMA_ESPANOL || idioma == BloqueEntity.IDIOMA_LATIN) {
                "idioma '$idioma' desconocido"
            }
            db.bloqueDao().insertar(
                BloqueEntity(
                    sesionId = sesionId,
                    indice = indice,
                    inicioMs = System.currentTimeMillis(),
                    idioma = idioma
                )
            )
        }

    override suspend fun cerrarBloque(
        bloqueId: Long,
        pulsaciones: Int,
        errores: Int,
        borrados: Int,
        ppm: Float,
        precision: Float,
        parrafosUsados: List<String>,
        interrumpido: Boolean,
        motivoInterrupcion: String
    ) = withContext(Dispatchers.IO) {
        db.bloqueDao().cerrar(
            id = bloqueId,
            finMs = System.currentTimeMillis(),
            pulsaciones = pulsaciones,
            errores = errores,
            borrados = borrados,
            ppm = ppm,
            precision = precision,
            parrafos = parrafosUsados.joinToString(","),
            interrumpido = interrumpido,
            motivo = motivoInterrupcion
        )
    }

    override suspend fun bloquesDe(sesionId: Long): List<BloqueEntity> =
        withContext(Dispatchers.IO) { db.bloqueDao().de(sesionId) }

    override suspend fun parrafosVistosPor(participanteId: Long): Set<String> =
        withContext(Dispatchers.IO) {
            db.bloqueDao().parrafosUsadosPor(participanteId)
                .flatMap { it.split(',') }
                .mapNotNull { it.trim().ifEmpty { null } }
                .toSet()
        }

    // --- datos ---------------------------------------------------------

    /**
     * NO lleva `runCatching`.
     *
     * Es la misma decisión que en `LabeledSessionRepositoryImpl`: perder
     * muestras en silencio dejaría un bloque con huecos que el análisis
     * interpretaría como pausas del participante. Si la escritura falla, la
     * captura tiene que enterarse y la sesión marcarse.
     */
    override suspend fun guardarMuestras(muestras: List<MuestraInercialEntity>) =
        withContext(Dispatchers.IO) {
            if (muestras.isEmpty()) return@withContext
            db.bloqueDao().insertarMuestras(muestras)
        }

    override suspend fun guardarEventos(eventos: List<EventoTecleoEntity>) =
        withContext(Dispatchers.IO) {
            if (eventos.isEmpty()) return@withContext
            db.bloqueDao().insertarEventos(eventos)
        }

    override suspend fun guardarCovariables(filas: List<CovariableSesionEntity>) =
        withContext(Dispatchers.IO) {
            if (filas.isEmpty()) return@withContext
            db.sesionControladaDao().insertarCovariables(filas)
        }

    override suspend fun muestrasDe(bloqueId: Long): List<MuestraInercialEntity> =
        withContext(Dispatchers.IO) { db.bloqueDao().muestrasDe(bloqueId) }

    override suspend fun eventosDe(bloqueId: Long): List<EventoTecleoEntity> =
        withContext(Dispatchers.IO) { db.bloqueDao().eventosDe(bloqueId) }

    override suspend fun tasaEfectivaHz(bloqueId: Long): Double? =
        withContext(Dispatchers.IO) { db.bloqueDao().tasaEfectivaHz(bloqueId) }

    override suspend fun cuantasMuestras(bloqueId: Long): Int =
        withContext(Dispatchers.IO) { db.bloqueDao().cuantasMuestras(bloqueId) }

    private companion object {
        const val TAG = "SesionControlada"
    }
}
