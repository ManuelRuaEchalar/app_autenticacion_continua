package com.example.autenticacioncontinua.ml.training

import android.os.SystemClock
import android.util.Log
import com.example.autenticacioncontinua.domain.ml.SensorWindow
import com.example.autenticacioncontinua.ml.data.BackgroundPool
import com.example.autenticacioncontinua.ml.data.WindowEnergy
import com.example.autenticacioncontinua.ml.model.HeadStore
import com.example.autenticacioncontinua.monitoring.Cronometro
import com.example.autenticacioncontinua.monitoring.MedidorDeOperacion
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Resultado de un `fit` local.
 *
 * @param encoderWeights lo ÚNICO que vuelve al servidor.
 * @param numExamples ventanas usadas (genuinas + impostoras). Es el peso con
 *   el que FedAvg pondera la contribución de este cliente, así que tiene que
 *   reflejar el volumen real de entrenamiento, no el de ventanas genuinas.
 */
data class TrainingResult(
    val encoderWeights: FloatArray,
    val numExamples: Int,
    val loss: Float,
    val reconLoss: Float,
    val clsLoss: Float,
    val steps: Int
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Entrenamiento local de una ronda federada.
 *
 * Réplica de `AuthClient.fit` (mejor.py:703-721):
 *
 *  1. Se inyecta el encoder agregado que llega del servidor.
 *  2. Se recupera la cabeza personal del disco (nunca viaja por la red).
 *  3. Se entrena el modelo COMPLETO —encoder y cabeza— durante `local_epochs`.
 *  4. Se guarda la cabeza y se devuelven sólo los pesos del encoder.
 *
 * Los negativos salen del pool de impostores reales empaquetado en el APK.
 * La versión anterior los fabricaba perturbando las ventanas del propio
 * usuario con ruido y barajando sus filas: eso enseña al clasificador a
 * distinguir "señal suave" de "señal ruidosa", no a un usuario de otro, y no
 * se parece en nada a lo que aprende la simulación.
 */
class LocalTrainer(
    private val modelManager: TFLiteModelManager,
    private val backgroundPool: BackgroundPool,
    private val headStore: HeadStore,
    /** Ver la nota de `ContinuousAuthenticator`: por defecto, uno propio. */
    private val cronometro: Cronometro = Cronometro()
) {
    private val manifest = modelManager.manifest

    /**
     * @param emparejarImpostores si muestrear el fondo emparejado por energía
     *   con las genuinas. Con `false` se muestrea al azar, que es el
     *   comportamiento anterior a la v1.6 y la condición "línea base" del
     *   experimento de ablación. Lo dicta el servidor.
     */
    suspend fun fit(
        globalEncoder: FloatArray,
        trainWindows: List<SensorWindow>,
        learningRate: Float,
        epochs: Int = manifest.localEpochs,
        emparejarImpostores: Boolean = true,
        seed: Long
    ): TrainingResult = withContext(Dispatchers.Default) {

        require(trainWindows.isNotEmpty()) { "fit() sin ventanas de entrenamiento" }
        // Con `epochs = 0` el bucle no itera: `fit` devolvería el encoder que
        // acaba de llegar del servidor, sin tocar, con loss=0.0 y sin un solo
        // paso de gradiente. FedAvg promediaría copias del mismo vector y la
        // federación entera no aprendería NADA, sin un error en ningún log.
        // Ocurrió durante 18 rondas por un desajuste de campos en el proto
        // (ver la nota de `Scalar` en transport.proto). Una ronda que no puede
        // entrenar debe fallar a gritos, no devolver un encoder intacto.
        require(epochs > 0) {
            "fit() con epochs=$epochs. El servidor no envió un `local_epochs` " +
                "legible o el manifiesto lo declara a cero."
        }
        require(learningRate > 0f) {
            "fit() con learningRate=$learningRate: los pesos no se moverían."
        }
        check(!backgroundPool.isEmpty()) {
            "El pool de impostores está vacío: sin negativos, la rama de " +
                "clasificación colapsa a predecir siempre 'genuino'. Regenera " +
                "los assets con --background-train."
        }

        val genuinePerBatch = manifest.trainGenuinePerBatch
        val backgroundPerBatch = manifest.trainBackgroundPerBatch

        val nanosInicio = SystemClock.elapsedRealtimeNanos()
        modelManager.setEncoderWeights(globalEncoder)
        headStore.load()?.let { modelManager.setHeadWeights(it) }
        if (manifest.resetOptimizerEachRound) modelManager.resetOptimizer()
        modelManager.setLearningRate(learningRate)

        val random = Random(seed)
        val genuine = trainWindows.map { it.values }
        // Con `background_ratio = 1.0`, tantos impostores como genuinos.
        val backgroundCount = (genuine.size * manifest.backgroundRatio).toInt()
        val stepsPerEpoch =
            ((genuine.size + genuinePerBatch - 1) / genuinePerBatch).coerceAtLeast(1)

        var lastLoss = 0f
        var lastRecon = 0f
        var lastCls = 0f
        var totalSteps = 0

        // Energía de cada genuina, calculada UNA vez: es inmutable y este
        // proyecto mide consumo de batería, así que recalcularla en cada época
        // sería gastar justo lo que se está midiendo.
        val energiasGenuinas = FloatArray(genuine.size) {
            WindowEnergy.accStdMedio(genuine[it], manifest.nFeatures)
        }

        for (epoch in 1..epochs) {
            // Se baraja el ÍNDICE, no la lista, para poder reordenar las
            // energías con la misma permutación y que `background[i]` siga
            // emparejado con `shuffled[i]`.
            val orden = genuine.indices.shuffled(random)
            val shuffled = orden.map { genuine[it] }
            // Impostoras emparejadas por energía con las genuinas de esta
            // época. Sin esto, el entrenamiento aprende en parte a separar
            // "quieto" de "en movimiento" —que es como difieren nuestros datos
            // de los de HMOG— en lugar de separar personas.
            val background = if (emparejarImpostores) {
                backgroundPool.sampleMatched(
                    FloatArray(orden.size) { energiasGenuinas[orden[it]] }, random
                )
            } else {
                backgroundPool.sample(backgroundCount, random)
            }

            var epochLoss = 0f
            var epochRecon = 0f
            var epochCls = 0f

            for (step in 0 until stepsPerEpoch) {
                coroutineContext.ensureActive()

                val genuineBatch = takeCyclic(shuffled, step * genuinePerBatch, genuinePerBatch)
                val backgroundBatch = if (background.size >= backgroundPerBatch) {
                    // Mismo offset y mismo tamaño que el lote genuino, así que
                    // la pareja por energía se conserva dentro del lote.
                    takeCyclic(background, step * backgroundPerBatch, backgroundPerBatch)
                } else {
                    // Menos genuinas que un lote: se completa emparejando
                    // contra las energías de ESTE lote, para no perder el
                    // balance justo en el caso degenerado.
                    if (emparejarImpostores) {
                        backgroundPool.sampleMatched(
                            FloatArray(genuineBatch.size) {
                                WindowEnergy.accStdMedio(genuineBatch[it], manifest.nFeatures)
                            },
                            random
                        )
                    } else {
                        backgroundPool.sample(backgroundPerBatch, random)
                    }
                }

                val result = modelManager.trainStep(genuineBatch, backgroundBatch)
                epochLoss += result.loss
                epochRecon += result.reconLoss
                epochCls += result.clsLoss
                totalSteps++
            }

            lastLoss = epochLoss / stepsPerEpoch
            lastRecon = epochRecon / stepsPerEpoch
            lastCls = epochCls / stepsPerEpoch
            Log.d(TAG, "época $epoch/$epochs: loss=$lastLoss recon=$lastRecon cls=$lastCls")
        }

        headStore.save(modelManager.getHeadWeights())
        // Cubre la carga de pesos, todas las épocas y el guardado de la
        // cabeza: es el coste de una ronda local completa visto desde quien
        // la pide. No se registra si `fit` lanza, porque una ronda que falló
        // no es una latencia de entrenamiento sino un error.
        cronometro.registrarNanos(
            MedidorDeOperacion.ENTRENAMIENTO_LOCAL,
            SystemClock.elapsedRealtimeNanos() - nanosInicio
        )

        TrainingResult(
            encoderWeights = modelManager.getEncoderWeights(),
            numExamples = genuine.size + backgroundCount,
            loss = lastLoss,
            reconLoss = lastRecon,
            clsLoss = lastCls,
            steps = totalSteps
        )
    }

    /**
     * Toma `count` elementos a partir de `from`, dando la vuelta al llegar al
     * final.
     *
     * El lote es de tamaño fijo, así que el último de cada época se completa
     * reutilizando ventanas del principio en lugar de descartarse. Con pocas
     * ventanas por dispositivo, tirar el resto significaría perder una
     * fracción apreciable de los datos del usuario en cada época.
     */
    private fun takeCyclic(source: List<FloatArray>, from: Int, count: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>(count)
        for (i in 0 until count) out.add(source[(from + i) % source.size])
        return out
    }

    private companion object {
        const val TAG = "LocalTrainer"
    }
}
