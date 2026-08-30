package com.example.autenticacioncontinua

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity
import com.example.autenticacioncontinua.data.repository.ParticipanteRepositoryImpl
import com.example.autenticacioncontinua.data.repository.SesionControladaRepositoryImpl
import com.example.autenticacioncontinua.domain.repository.ResultadoAlta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Comportamiento del corpus controlado sobre una base Room de verdad.
 *
 * En memoria y no en disco: aquí no se comprueba la persistencia entre
 * arranques —eso es de `MigracionCampoTest`— sino las garantías del esquema,
 * que SQLite aplica igual en memoria: unicidad del seudónimo, borrado en
 * cascada y rendimiento de la inserción por lotes.
 *
 * NECESITA UN DISPOSITIVO O EMULADOR. Room no funciona en la JVM.
 */
@RunWith(AndroidJUnit4::class)
class CorpusControladoTest {

    private lateinit var db: AppDatabase
    private lateinit var participantes: ParticipanteRepositoryImpl
    private lateinit var sesiones: SesionControladaRepositoryImpl

    @Before
    fun abrir() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            // Sin registro de escritura anticipada: en una base en memoria el
            // WAL no aporta nada y su hilo de fondo hace que el cierre entre
            // pruebas no sea determinista. Las claves ajenas las activa Room
            // por su cuenta (`PRAGMA foreign_keys = ON` al abrir), que es lo
            // que hace efectiva la cascada que se comprueba mas abajo.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        participantes = ParticipanteRepositoryImpl(db)
        sesiones = SesionControladaRepositoryImpl(db, "test", "1.0")
    }

    @After
    fun cerrar() = db.close()

    // ------------------------------------------------------------------
    // Participantes
    // ------------------------------------------------------------------

    @Test
    fun altaDeParticipanteYRecuperacion() = runBlocking {
        val r = participantes.alta("P01", "25-34", "f", "diestra", "ninguna")
        assertTrue(r is ResultadoAlta.Creado)

        val p = participantes.porSeudonimo("p01")   // se normaliza
        assertNotNull(p)
        assertEquals("P01", p!!.seudonimo)
        assertEquals(0, p.sesionesHechas)
    }

    /**
     * Dos filas para la misma persona la partirían en dos identidades, y en un
     * análisis con partición disjunta por persona eso pone a la misma persona a
     * los dos lados de la partición: fuga.
     */
    @Test
    fun seudonimoDuplicadoSeRechazaYDevuelveElExistente() = runBlocking {
        participantes.alta("P01", "25-34", "f", "diestra", "ninguna")
        val segunda = participantes.alta("  p01 ", "35-44", "m", "zurda", "alta")

        assertTrue(segunda is ResultadoAlta.SeudonimoDuplicado)
        assertEquals("P01", (segunda as ResultadoAlta.SeudonimoDuplicado).existente.seudonimo)
        assertEquals(1, db.participanteDao().cuantos())
        // Y el existente NO se ha modificado con los datos del alta repetida.
        assertEquals("25-34", participantes.porSeudonimo("P01")!!.tramoEdad)
    }

    @Test
    fun seudonimoInvalidoSeRechaza() = runBlocking {
        for (malo in listOf("", " ", "P", "participante con espacios", "P".repeat(20))) {
            val r = participantes.alta(malo, "25-34", "f", "diestra", "ninguna")
            assertTrue("'$malo' deberia rechazarse", r is ResultadoAlta.SeudonimoInvalido)
        }
        assertEquals(0, db.participanteDao().cuantos())
    }

    // ------------------------------------------------------------------
    // Cascada
    // ------------------------------------------------------------------

    /**
     * Sin cascada quedarían veintitantos millones de filas huérfanas que
     * ninguna consulta encontraría y que seguirían ocupando el disco del
     * teléfono que se presta a los participantes.
     */
    @Test
    fun borrarUnParticipanteSeLlevaSesionesBloquesMuestrasYEventos() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", semilla = 1L, bateriaInicio = 80f)
        val bloqueId = sesiones.abrirBloque(sesionId, 0, BloqueEntity.IDIOMA_ESPANOL)
        sesiones.guardarMuestras(muestras(bloqueId, 100))
        sesiones.guardarEventos(eventos(bloqueId, 20))

        assertEquals(100, sesiones.cuantasMuestras(bloqueId))

        participantes.borrar(id)

        assertNull(db.sesionControladaDao().porId(sesionId))
        assertTrue(db.bloqueDao().de(sesionId).isEmpty())
        assertEquals(0, db.bloqueDao().cuantasMuestras(bloqueId))
        assertEquals(0, db.bloqueDao().cuantosEventos(bloqueId))
    }

    // ------------------------------------------------------------------
    // Sesiones
    // ------------------------------------------------------------------

    @Test
    fun elPlanAlternaDispositivoYAvisaSiElTerminalNoEsElEsperado() = runBlocking {
        val id = crearParticipante("P01")   // impar: empieza por A

        val plan1 = sesiones.planificar(id, dispositivoReal = "A")!!
        assertEquals(1, plan1.visita)
        assertEquals("A", plan1.dispositivoEsperado)
        assertTrue(!plan1.dispositivoNoEsElEsperado)

        val s1 = sesiones.abrir(id, "A", 1L, 80f)
        sesiones.cerrar(s1, EstadoSesion.COMPLETA, 78f)

        val plan2 = sesiones.planificar(id, dispositivoReal = "A")!!
        assertEquals(2, plan2.visita)
        assertEquals("B", plan2.dispositivoEsperado)
        assertTrue("con el terminal equivocado debe avisar", plan2.dispositivoNoEsElEsperado)
    }

    /**
     * Una sesión que salió mal se marca; no se borra. Borrar destruye la
     * trazabilidad —el recuento dejaría de cuadrar con el cuaderno de campo— y
     * además invita a borrar lo que no gusta.
     */
    @Test
    fun invalidarConservaLaFilaYSuMotivo() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", 1L, 80f)
        sesiones.invalidar(sesionId, "participante equivocado")

        val s = sesiones.sesion(sesionId)!!
        assertEquals(EstadoSesion.INVALIDADA.name, s.estado)
        assertEquals("participante equivocado", s.motivoInvalidacion)
        // Y no cuenta como sesión utilizable del participante.
        assertEquals(0, participantes.todos().single().sesionesHechas)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidarSinMotivoEsUnError() = runBlocking {
        val id = crearParticipante("P01")
        sesiones.invalidar(sesiones.abrir(id, "A", 1L, 80f), "   ")
        Unit
    }

    /**
     * Si la app muere a mitad, la sesión queda EN_CURSO. Dejarla así haría que
     * la siguiente visita pareciera su continuación, y los bloques de dos días
     * distintos acabarían en el mismo episodio.
     */
    @Test
    fun lasSesionesHuerfanasSeCierranComoAbortadasConservandoSusBloques() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", 1L, 80f)
        val bloqueId = sesiones.abrirBloque(sesionId, 0, BloqueEntity.IDIOMA_ESPANOL)
        sesiones.guardarMuestras(muestras(bloqueId, 50))
        sesiones.cerrarBloque(bloqueId, 100, 3, 2, 42f, 0.97f, listOf("es_001"))

        assertEquals(1, sesiones.cerrarHuerfanas())

        val s = sesiones.sesion(sesionId)!!
        assertEquals(EstadoSesion.ABORTADA.name, s.estado)
        assertTrue("el fin no puede ser 0", s.finMs > 0)
        assertEquals("los bloques completos se conservan", 50, sesiones.cuantasMuestras(bloqueId))
        // Y una segunda llamada no encuentra nada que cerrar.
        assertEquals(0, sesiones.cerrarHuerfanas())
    }

    // ------------------------------------------------------------------
    // Volumen
    // ------------------------------------------------------------------

    /**
     * 100 000 muestras son unos 17 minutos a 100 Hz: algo más que una sesión
     * entera. Si esto no va rápido, la escritura no puede seguir el ritmo de la
     * captura mientras el participante teclea — y el coste caería además dentro
     * de la medición de consumo, que es una variable dependiente del estudio.
     */
    @Test
    fun insercionDeCienMilMuestrasPorLotes() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", 1L, 80f)
        val bloqueId = sesiones.abrirBloque(sesionId, 0, BloqueEntity.IDIOMA_ESPANOL)

        val total = 100_000
        val ms = measureTimeMillis {
            var escritas = 0
            while (escritas < total) {
                val n = minOf(MuestraInercialEntity.LOTE, total - escritas)
                sesiones.guardarMuestras(muestras(bloqueId, n, desde = escritas))
                escritas += n
            }
        }

        assertEquals(total, sesiones.cuantasMuestras(bloqueId))
        android.util.Log.i(TAG, "100 000 muestras por lotes de ${MuestraInercialEntity.LOTE}: $ms ms")
        assertTrue("tardo $ms ms, demasiado para seguir la captura", ms < 60_000)
    }

    @Test
    fun laTasaEfectivaSeCalculaConElRelojMonotono() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", 1L, 80f)
        val bloqueId = sesiones.abrirBloque(sesionId, 0, BloqueEntity.IDIOMA_ESPANOL)
        // 1 000 muestras separadas 10 ms -> 100 Hz exactos.
        sesiones.guardarMuestras(muestras(bloqueId, 1_000, periodoNs = 10_000_000L))

        assertEquals(100.0, sesiones.tasaEfectivaHz(bloqueId)!!, 0.01)
    }

    @Test
    fun sinMuestrasLaTasaEsNulaYNoCero() = runBlocking {
        val id = crearParticipante("P01")
        val sesionId = sesiones.abrir(id, "A", 1L, 80f)
        val bloqueId = sesiones.abrirBloque(sesionId, 0, BloqueEntity.IDIOMA_ESPANOL)

        assertNull(sesiones.tasaEfectivaHz(bloqueId))
    }

    // ------------------------------------------------------------------

    private suspend fun crearParticipante(seudonimo: String): Long {
        db.participanteDao().insertar(
            ParticipanteEntity(
                seudonimo = seudonimo,
                fechaAltaMs = 0L,
                tramoEdad = "25-34",
                sexo = "f",
                lateralidad = "diestra",
                competenciaLatin = "ninguna"
            )
        )
        return db.participanteDao().porSeudonimo(seudonimo)!!.id
    }

    private fun muestras(
        bloqueId: Long,
        n: Int,
        desde: Int = 0,
        periodoNs: Long = 10_000_000L
    ) = List(n) { i ->
        val k = (desde + i).toLong()
        MuestraInercialEntity(
            bloqueId = bloqueId,
            tParedMs = k * 10,
            tMonotonoNs = k * periodoNs,
            accX = 0.1f, accY = 0.2f, accZ = 9.8f,
            gyrX = 0.01f, gyrY = 0.02f, gyrZ = 0.03f,
            magX = 12f, magY = -3f, magZ = 40f
        )
    }

    private fun eventos(bloqueId: Long, n: Int) = List(n) { i ->
        EventoTecleoEntity(
            bloqueId = bloqueId,
            parrafoId = "es_001",
            posicion = i,
            esperado = "a",
            recibido = "a",
            acierto = true,
            tDownMs = i * 200L,
            tUpMs = i * 200L + 90
        )
    }

    private companion object {
        const val TAG = "CorpusControlado"
    }
}
