package com.example.autenticacioncontinua.ml.inference

import android.util.Log
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.ml.model.HeadStore
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.model.ThresholdStore
import com.example.autenticacioncontinua.monitoring.Cronometro
import com.example.autenticacioncontinua.monitoring.MedidorDeOperacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Veredicto de autenticación sobre las ventanas más recientes.
 *
 * @param genuineScore probabilidad media de que quien sostiene el teléfono
 *   sea el usuario legítimo.
 * @param reconstructionError error del autoencoder. Alto significa "esta
 *   señal no se parece a nada que este usuario haya producido"; sirve de
 *   señal complementaria al clasificador, que es lo que aporta la rama
 *   multi-tarea del modelo.
 * @param windowsEvaluated ventanas que respaldan el veredicto.
 */
data class AuthenticationVerdict(
    val isGenuine: Boolean,
    val genuineScore: Float,
    val reconstructionError: Float,
    val threshold: Float,
    val windowsEvaluated: Int
)

/**
 * Autenticación continua en tiempo real.
 *
 * Usa la firma `infer` del `.tflite` (lote de 1) con el encoder federado
 * vigente y la cabeza personal del usuario, aplicando el umbral CALIBRADO.
 *
 * La decisión se toma sobre la media de varias ventanas consecutivas y no
 * sobre una sola: a 50 Hz una ventana son 2,56 s de señal, y un movimiento
 * atípico aislado —dejar el teléfono en la mesa, un tropiezo— no debería
 * bastar para declarar impostor a nadie.
 */
class ContinuousAuthenticator(
    private val modelManager: TFLiteModelManager,
    private val windowSegmenter: IWindowSegmenter,
    private val headStore: HeadStore,
    private val thresholdStore: ThresholdStore,
    /**
     * Cronómetro compartido donde se acumulan las latencias.
     *
     * Lleva valor por defecto para que construirlo en una prueba no obligue
     * a inyectar nada; en la app Koin pasa el compartido, que es el que se
     * vuelca a la base al cerrar la sesión.
     */
    private val cronometro: Cronometro = Cronometro()
) {

    /**
     * Evalúa las últimas [windowCount] ventanas disponibles.
     *
     * @return `null` si no hay señal reciente suficiente.
     */
    suspend fun authenticate(windowCount: Int = DEFAULT_WINDOW_COUNT): AuthenticationVerdict? =
        withContext(Dispatchers.Default) {
            val windows = windowSegmenter.getWindows()
            if (windows.isEmpty()) {
                Log.d(TAG, "Sin ventanas recientes para autenticar")
                return@withContext null
            }
            authenticate(windows.takeLast(windowCount))
        }

    /** Variante para una lista de ventanas ya construida. */
    fun authenticate(windows: List<SensorWindow>): AuthenticationVerdict? {
        if (windows.isEmpty()) return null

        // La cabeza personal vive en disco: sin ella, el clasificador sería
        // el de inicialización aleatoria y el veredicto, ruido.
        headStore.load()?.let { modelManager.setHeadWeights(it) }
            ?: Log.w(TAG, "No hay cabeza personal entrenada; el veredicto no es fiable")

        val threshold = thresholdStore.threshold
        var scoreSum = 0f
        var reconSum = 0f
        for (window in windows) {
            // Por ventana, no por lote: la latencia que importa al usuario
            // es la de una decisión, y promediar el lote la escondería.
            val result = cronometro.medir(MedidorDeOperacion.INFERENCIA_VENTANA) {
                modelManager.score(window.values, threshold)
            }
            scoreSum += result.genuineScore
            reconSum += result.reconstructionError
        }

        val meanScore = scoreSum / windows.size
        return AuthenticationVerdict(
            isGenuine = meanScore >= threshold,
            genuineScore = meanScore,
            reconstructionError = reconSum / windows.size,
            threshold = threshold,
            windowsEvaluated = windows.size
        )
    }

    private companion object {
        const val TAG = "ContinuousAuthenticator"

        /** ~5 ventanas de 2,56 s: unos 13 s de comportamiento observado. */
        const val DEFAULT_WINDOW_COUNT = 5
    }
}
