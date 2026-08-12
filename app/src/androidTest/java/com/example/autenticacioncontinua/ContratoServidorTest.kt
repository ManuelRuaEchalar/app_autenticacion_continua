package com.example.autenticacioncontinua

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.federated.ModelInfoFetcher
import com.example.autenticacioncontinua.federated.requireCompatibleWith
import com.example.autenticacioncontinua.ml.model.ModelManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * FASE 4 — el contrato servidor↔APK sobre la red real.
 *
 * `FederatedLearningService` comprueba la compatibilidad DESPUÉS de preparar
 * el dataset local, así que hasta que el dispositivo acumule sesiones de uso
 * no se llega a esa comprobación por la vía normal. Esta prueba la ejercita
 * directamente: misma clase, misma red, mismo backend.
 *
 * Requiere el backend corriendo en `BuildConfig.SERVER_HOST`. Si no responde,
 * la prueba se OMITE en vez de fallar: sin servidor no está probando nada del
 * APK, y un rojo ahí sólo confunde al ejecutar la suite sin backend.
 */
@RunWith(AndroidJUnit4::class)
class ContratoServidorTest {

    private lateinit var manifest: ModelManifest

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manifest = ModelManifest.fromAssets(context)
    }

    @Test
    fun elServidorPublicaElMismoContratoQueElManifiestoDelApk() = runBlocking {
        val info = try {
            ModelInfoFetcher().fetchModelInfo()
        } catch (e: IOException) {
            assumeNoException(
                "Backend no alcanzable en ${BuildConfig.SERVER_HOST}. " +
                    "Arranca 'python run.py' en el PC y comprueba que el " +
                    "teléfono está en la misma red.",
                e
            )
            return@runBlocking
        }

        println(
            "Contrato del servidor: sensor_config=${info.sensorConfig} " +
                "window_size=${info.windowSize} encoder_flat_size=${info.encoderFlatSize}"
        )

        // Antes de la Fase 3 esto valía null y el servidor decía "gyro_acc":
        // son los dos motivos exactos por los que la app abortaba la ronda.
        assertNotNull(
            "El servidor no publica encoder_flat_size: sigue con el esquema " +
                "anterior a la Fase 3",
            info.encoderFlatSize
        )
        assertEquals(manifest.sensorConfig, info.sensorConfig)
        assertEquals(manifest.windowSize, info.windowSize)
        assertEquals(manifest.encoderFlatSize, info.encoderFlatSize)

        // La comprobación real que hace FederatedLearningService:
        // lanza IllegalStateException si algo no cuadra.
        info.requireCompatibleWith(manifest)
        println("requireCompatibleWith pasó: el APK entrenaría contra este servidor")
    }
}
