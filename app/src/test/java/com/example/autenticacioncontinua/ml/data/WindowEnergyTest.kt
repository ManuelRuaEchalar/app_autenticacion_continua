package com.example.autenticacioncontinua.ml.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * Valida que el estadístico de energía implementado en el dispositivo produce
 * LOS MISMOS NÚMEROS que el análisis offline con el que se eligieron el umbral
 * del filtro de actividad y la tolerancia del emparejado.
 *
 * Sin esta comprobación, las constantes del código estarían justificadas por un
 * análisis en Python que el Kotlin podría no estar reproduciendo. Es el mismo
 * tipo de desajuste silencioso que costó 18 rondas de entrenamiento vacío: dos
 * implementaciones de un contrato, ninguna que verifique que coinciden.
 *
 * Los valores de referencia salen de:
 *   np.fromfile(background_train.bin, float32).reshape(-1,128,6)
 *      .std(axis=1)[:, :3].mean(1)
 */
class WindowEnergyTest {

    private val nFeatures = 6
    private val windowSize = 128

    private fun cargarPool(): List<FloatArray> {
        val f = File("src/main/assets/background_train.bin")
        assertTrue(
            "No se encuentra ${f.absolutePath}; el test necesita el asset real",
            f.exists()
        )
        val bytes = f.readBytes()
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        val porVentana = windowSize * nFeatures
        return (0 until floats.size / porVentana).map {
            floats.copyOfRange(it * porVentana, (it + 1) * porVentana)
        }
    }

    @Test
    fun `reproduce los percentiles del analisis offline sobre HMOG`() {
        val ventanas = cargarPool()
        assertEquals(600, ventanas.size)

        val energias = FloatArray(ventanas.size) {
            WindowEnergy.accStdMedio(ventanas[it], nFeatures)
        }

        // Tolerancia estrecha: se está comprobando que dos implementaciones del
        // MISMO cálculo coinciden, no que se parezcan.
        val tol = 1e-4
        assertEquals(0.047101, WindowEnergy.percentil(energias, 5.0).toDouble(), tol)
        assertEquals(0.128915, WindowEnergy.percentil(energias, 25.0).toDouble(), tol)
        assertEquals(0.246508, WindowEnergy.percentil(energias, 50.0).toDouble(), tol)
        assertEquals(0.372215, WindowEnergy.percentil(energias, 75.0).toDouble(), tol)
        assertEquals(0.543786, WindowEnergy.percentil(energias, 95.0).toDouble(), tol)
    }

    @Test
    fun `la energia por ventana coincide ventana a ventana`() {
        val ventanas = cargarPool()
        assertEquals(
            0.277224,
            WindowEnergy.accStdMedio(ventanas.first(), nFeatures).toDouble(),
            1e-5
        )
        assertEquals(
            0.293437,
            WindowEnergy.accStdMedio(ventanas.last(), nFeatures).toDouble(),
            1e-5
        )
    }

    @Test
    fun `usa desviacion por eje y no la magnitud`() {
        // Rotación pura simulada: la gravedad se reparte distinto entre ejes
        // pero el módulo |a| se mantiene constante. Con la magnitud esto
        // marcaría energía ~0; por eje tiene que verse.
        val valores = FloatArray(windowSize * nFeatures)
        for (t in 0 until windowSize) {
            val ang = 2.0 * Math.PI * t / windowSize
            valores[t * nFeatures + 0] = (9.8 * Math.cos(ang)).toFloat()
            valores[t * nFeatures + 1] = (9.8 * Math.sin(ang)).toFloat()
            valores[t * nFeatures + 2] = 0f
        }
        val e = WindowEnergy.accStdMedio(valores, nFeatures)
        assertTrue("Una rotación pura debe producir energía apreciable, dio $e", e > 1.0f)
    }

    @Test
    fun `una ventana constante tiene energia cero`() {
        val valores = FloatArray(windowSize * nFeatures) { 5f }
        assertEquals(0.0, WindowEnergy.accStdMedio(valores, nFeatures).toDouble(), 1e-6)
    }

    @Test
    fun `el emparejado equilibra la energia entre clases`() {
        // Reproduce en Kotlin el resultado que motivó el diseño: sin emparejar,
        // la energía sola separa los conjuntos; emparejando, no.
        val ventanas = cargarPool()
        val pool = BackgroundPool.fromFloatsForTest(ventanas, windowSize * nFeatures, nFeatures)

        // "Genuinas" sintéticas: la mitad baja del propio pool, que es como se
        // comportan nuestras ventanas frente a HMOG (más quietas).
        val energias = FloatArray(ventanas.size) {
            WindowEnergy.accStdMedio(ventanas[it], nFeatures)
        }
        val genuinas = energias.sorted().take(ventanas.size / 2).toFloatArray()

        val random = Random(0)
        val sinEmparejar = pool.sample(genuinas.size, random)
            .map { WindowEnergy.accStdMedio(it, nFeatures) }.toFloatArray()
        val emparejadas = pool.sampleMatched(genuinas, random)
            .map { WindowEnergy.accStdMedio(it, nFeatures) }.toFloatArray()

        val aucSin = auc(genuinas, sinEmparejar)
        val aucCon = auc(genuinas, emparejadas)

        assertTrue(
            "Sin emparejar la energía debería separar claramente (dio $aucSin)",
            aucSin > 0.70
        )
        assertTrue(
            "Emparejando la energía no debería separar (dio $aucCon)",
            abs(aucCon - 0.5) < 0.05
        )
    }

    /** AUC de Mann-Whitney: P(pos > neg), plegada a [0.5, 1]. */
    private fun auc(pos: FloatArray, neg: FloatArray): Double {
        val todo = (pos.map { it to 1 } + neg.map { it to 0 }).sortedBy { it.first }
        var sumaRangosPos = 0.0
        todo.forEachIndexed { i, (_, etiqueta) ->
            if (etiqueta == 1) sumaRangosPos += (i + 1)
        }
        val n1 = pos.size.toDouble()
        val n2 = neg.size.toDouble()
        val a = (sumaRangosPos - n1 * (n1 + 1) / 2) / (n1 * n2)
        return maxOf(a, 1 - a)
    }
}
