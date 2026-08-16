package com.example.autenticacioncontinua

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.ml.WindowSegmenter
import com.example.autenticacioncontinua.data.repository.AccelerometerRepositoryImpl
import com.example.autenticacioncontinua.data.repository.GyroscopeRepositoryImpl
import com.example.autenticacioncontinua.data.repository.LabeledSessionRepositoryImpl
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.ml.data.FeatureScaler
import com.example.autenticacioncontinua.ml.model.ModelManifest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Que la exclusión de tramos etiquetados llegue de verdad hasta las ventanas.
 *
 * La lógica pura ya está cubierta por `ExclusionEtiquetadaTest` en `src/test`.
 * Lo que se prueba AQUÍ es el camino completo con una base Room real:
 * repositorio -> `WindowSegmenter` -> ventanas. Es donde vive el fallo que más
 * caro sale, porque no produce excepción ni síntoma: si el cableado se rompe,
 * las ventanas del impostor entran en el conjunto genuino del dueño y las
 * métricas dejan de significar lo que dicen.
 *
 * Ejecutar con:  ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExclusionEtiquetadaEnSegmenterTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var segmenter: WindowSegmenter
    private lateinit var labeled: LabeledSessionRepositoryImpl

    /** Base t0: hace una hora, dentro del histórico de 14 días. */
    private val t0 = System.currentTimeMillis() - 60 * 60 * 1000L

    /** Separación entre bloques, por encima de los 30 s que cortan sesión. */
    private val hueco = 60_000L

    private val bloqueMs = 120_000L

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        labeled = LabeledSessionRepositoryImpl(db)
        segmenter = WindowSegmenter(
            accelerometerRepository = AccelerometerRepositoryImpl(db),
            gyroscopeRepository = GyroscopeRepositoryImpl(db),
            labeledSessionRepository = labeled,
            manifest = ModelManifest.fromAssets(context),
            scaler = FeatureScaler.fromAssets(
                context = context,
                assetName = ModelManifest.fromAssets(context).scalerStatsFile,
                expectedFeatures = ModelManifest.fromAssets(context).nFeatures
            )
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * Escribe un bloque de señal a 50 Hz con movimiento suficiente para no caer
     * por el filtro de actividad.
     */
    private fun sembrarBloque(desde: Long, duracionMs: Long, amplitud: Float) = runBlocking {
        val n = (duracionMs / 20).toInt()
        val acc = ArrayList<AccelerometerData>(n)
        val gyr = ArrayList<GyroscopeData>(n)
        for (i in 0 until n) {
            val t = desde + i * 20L
            val v = amplitud * sin(i / 3.0).toFloat()
            acc.add(AccelerometerData(0, v, v + 1f, 9.8f + v, t, "test"))
            gyr.add(GyroscopeData(0, v, v, v, t, "test"))
        }
        AccelerometerRepositoryImpl(db).saveAccelerometerData(acc)
        GyroscopeRepositoryImpl(db).saveGyroscopeData(gyr)
    }

    @Test
    fun sinTramosEtiquetadosSalenVentanasDeLosDosBloques() = runBlocking {
        sembrarBloque(t0, bloqueMs, 2f)
        sembrarBloque(t0 + bloqueMs + hueco, bloqueMs, 2f)

        val ventanas = segmenter.getWindows(aplicarFiltroActividad = false)

        assertTrue("Debería haber ventanas de los dos bloques", ventanas.size > 20)
        assertEquals(
            "Los dos bloques separados por más de 30 s son dos sesiones",
            2, ventanas.map { it.sessionId }.distinct().size
        )
    }

    @Test
    fun lasVentanasDeUnImpostorNoLleganAlConjuntoGenuino() = runBlocking {
        sembrarBloque(t0, bloqueMs, 2f)

        // Segundo bloque: lo graba OTRA persona en este mismo teléfono.
        val inicioImpostor = t0 + bloqueMs + hueco
        sembrarBloque(inicioImpostor, bloqueMs, 2f)
        val fila = labeled.abrir("IMPOSTOR_1", isOwner = false, note = "prueba")
        db.labeledSessionDao().close(fila, inicioImpostor + bloqueMs)
        // `abrir` sella startMs con el reloj; se reescribe al instante simulado.
        db.compileStatement(
            "UPDATE labeled_sessions SET startMs = $inicioImpostor WHERE id = $fila"
        ).executeUpdateDelete()

        val ventanas = segmenter.getWindows(aplicarFiltroActividad = false)

        assertTrue("Debería quedar el bloque del dueño", ventanas.isNotEmpty())
        assertEquals(
            "Sólo puede quedar la sesión del dueño",
            1, ventanas.map { it.sessionId }.distinct().size
        )
        val ultima = ventanas.maxOf { it.startTimestampMs }
        assertTrue(
            "Ninguna ventana puede empezar dentro del tramo del impostor " +
                "(última=$ultima, tramo empieza en $inicioImpostor)",
            ultima < inicioImpostor
        )
    }

    @Test
    fun lasRafagasDeControlDelDuenoSiEntrenan() = runBlocking {
        sembrarBloque(t0, bloqueMs, 2f)

        val inicioControl = t0 + bloqueMs + hueco
        sembrarBloque(inicioControl, bloqueMs, 2f)
        val fila = labeled.abrir("DUENO", isOwner = true, note = "control")
        db.labeledSessionDao().close(fila, inicioControl + bloqueMs)
        db.compileStatement(
            "UPDATE labeled_sessions SET startMs = $inicioControl WHERE id = $fila"
        ).executeUpdateDelete()

        val ventanas = segmenter.getWindows(aplicarFiltroActividad = false)

        assertEquals(
            "El control del dueño es material genuino suyo y debe conservarse",
            2, ventanas.map { it.sessionId }.distinct().size
        )
        assertTrue(
            "Debe haber ventanas dentro del tramo de control",
            ventanas.any { it.startTimestampMs >= inicioControl }
        )
    }

    @Test
    fun unTramoSinCerrarTambienSeExcluye() = runBlocking {
        sembrarBloque(t0, bloqueMs, 2f)

        val inicioImpostor = t0 + bloqueMs + hueco
        sembrarBloque(inicioImpostor, bloqueMs, 2f)
        // endMs = 0: la app murió a mitad de la captura. Los datos se
        // escribieron igual, así que el tramo tiene que excluirse igual.
        val fila = labeled.abrir("IMPOSTOR_2", isOwner = false, note = "sin cerrar")
        db.compileStatement(
            "UPDATE labeled_sessions SET startMs = $inicioImpostor, endMs = 0 WHERE id = $fila"
        ).executeUpdateDelete()

        val ventanas = segmenter.getWindows(aplicarFiltroActividad = false)

        assertEquals(1, ventanas.map { it.sessionId }.distinct().size)
        assertTrue(ventanas.all { it.startTimestampMs < inicioImpostor })
    }
}
