package com.example.autenticacioncontinua.ml.training

import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

data class TrainingResult(
    val updatedParams: List<ByteBuffer>,
    val numSamples: Int,
    val loss: Float
)

class LocalTrainer(private val modelManager: TFLiteModelManager) {

    suspend fun train(
        positiveWindows: List<FloatArray>,
        globalParams: List<ByteBuffer>,
        epochs: Int = 5
    ): TrainingResult = withContext(Dispatchers.Default) {
        
        // Cargar parámetros globales
        modelManager.setParameters(globalParams)
        
        if (positiveWindows.isEmpty()) {
            return@withContext TrainingResult(globalParams, 0, 0f)
        }

        // Generar negativos sintéticos
        val negativeWindows = generateSyntheticNegatives(positiveWindows)
        
        var finalLoss = 0f
        
        for (epoch in 1..epochs) {
            finalLoss = modelManager.trainEpoch(positiveWindows, negativeWindows)
        }
        
        val updatedParams = modelManager.getParameters()
        
        TrainingResult(
            updatedParams = updatedParams,
            numSamples = positiveWindows.size + negativeWindows.size,
            loss = finalLoss
        )
    }

    fun generateSyntheticNegatives(positives: List<FloatArray>): List<FloatArray> {
        // Perturbar con ruido gaussiano σ=0.1 y permutar orden temporal
        return positives.map { window ->
            val noisy = window.copyOf()
            for (i in noisy.indices) {
                // simple uniform noise as pseudo-gaussian or simple random [-0.1, 0.1]
                noisy[i] += (Math.random() * 0.2 - 0.1).toFloat()
            }
            
            // Permute temporally: 
            // the window has shape (128, 6) flattened to 768 elements
            // To temporally shuffle, we can shuffle the 128 rows.
            val rows = mutableListOf<FloatArray>()
            var k = 0
            for (i in 0 until 128) {
                val row = FloatArray(6)
                for (j in 0 until 6) {
                    row[j] = noisy[k++]
                }
                rows.add(row)
            }
            
            rows.shuffle()
            
            val shuffledNoisy = FloatArray(noisy.size)
            k = 0
            for (i in 0 until 128) {
                val row = rows[i]
                for (j in 0 until 6) {
                    shuffledNoisy[k++] = row[j]
                }
            }
            
            shuffledNoisy
        }
    }
}
