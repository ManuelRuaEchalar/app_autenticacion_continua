package com.example.autenticacioncontinua.ml.metrics

import kotlin.math.abs
import kotlin.math.ln

/**
 * Un punto de la curva ROC: umbral, tasa de falsa aceptación y tasa de falso
 * rechazo.
 */
data class RocPoint(val threshold: Float, val far: Double, val frr: Double)

/**
 * Métricas de un clasificador binario genuino/impostor.
 *
 * Portan a Kotlin lo que en la simulación hacen `roc_curve`, `roc_auc_score`
 * y `calibrate_threshold_from_background` de scikit-learn. Se calculan sobre
 * los umbrales que realmente aparecen en los datos —igual que `roc_curve`—
 * en lugar de sobre una rejilla fija: con pocas ventanas por dispositivo, una
 * rejilla de 101 pasos redondea el EER de forma perceptible.
 */
class BinaryMetrics {

    /**
     * Curva ROC completa.
     *
     * @param scores puntuación de genuinidad en [0,1].
     * @param labels 1 = usuario legítimo, 0 = impostor.
     */
    fun rocCurve(scores: FloatArray, labels: IntArray): List<RocPoint> {
        require(scores.size == labels.size) {
            "scores (${scores.size}) y labels (${labels.size}) no coinciden"
        }
        val nPos = labels.count { it == 1 }
        val nNeg = labels.size - nPos
        if (nPos == 0 || nNeg == 0) return emptyList()

        val order = scores.indices.sortedByDescending { scores[it] }
        val points = ArrayList<RocPoint>(scores.size + 1)

        var tp = 0
        var fp = 0
        var i = 0
        while (i < order.size) {
            val threshold = scores[order[i]]
            // Todas las muestras con la misma puntuación se aceptan o se
            // rechazan juntas; tratarlas por separado inventaría puntos de la
            // curva que ningún umbral puede alcanzar.
            while (i < order.size && scores[order[i]] == threshold) {
                if (labels[order[i]] == 1) tp++ else fp++
                i++
            }
            points.add(
                RocPoint(
                    threshold = threshold,
                    far = fp.toDouble() / nNeg,
                    frr = 1.0 - tp.toDouble() / nPos
                )
            )
        }
        return points
    }

    /**
     * Área bajo la curva ROC por el estadístico U de Mann-Whitney, con
     * corrección por empates. Equivale a `roc_auc_score`.
     */
    fun auc(scores: FloatArray, labels: IntArray): Double {
        require(scores.size == labels.size)
        val nPos = labels.count { it == 1 }
        val nNeg = labels.size - nPos
        if (nPos == 0 || nNeg == 0) return 0.5

        val order = scores.indices.sortedBy { scores[it] }
        val ranks = DoubleArray(scores.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && scores[order[j + 1]] == scores[order[i]]) j++
            // Rango medio para los empates (1-indexado).
            val averageRank = (i + j + 2) / 2.0
            for (k in i..j) ranks[order[k]] = averageRank
            i = j + 1
        }

        var rankSumPos = 0.0
        for (idx in labels.indices) if (labels[idx] == 1) rankSumPos += ranks[idx]

        return (rankSumPos - nPos * (nPos + 1) / 2.0) / (nPos.toDouble() * nNeg)
    }

    /** Equal Error Rate: el punto de la ROC donde FAR y FRR se cruzan. */
    fun eer(scores: FloatArray, labels: IntArray): Double {
        val curve = rocCurve(scores, labels)
        if (curve.isEmpty()) return 1.0
        val best = curve.minByOrNull { abs(it.far - it.frr) } ?: return 1.0
        return (best.far + best.frr) / 2.0
    }

    /**
     * Umbral de decisión calibrado SIN mirar el conjunto de test.
     *
     * Réplica de `calibrate_threshold_from_background` (mejor.py:1232-1238):
     * se cruzan las puntuaciones de las ventanas genuinas de ENTRENAMIENTO
     * con las del pool de background de CALIBRACIÓN —sujetos disjuntos de los
     * que aportan los impostores de entrenamiento y evaluación— y se toma el
     * umbral donde FAR y FRR se igualan.
     *
     * Es el único umbral honesto de cara a producción: el "oracle" del
     * cuadernillo se elige a posteriori sobre las etiquetas de test, que en un
     * dispositivo real no existen.
     */
    fun calibrateThreshold(
        genuineScores: FloatArray,
        backgroundScores: FloatArray,
        fallback: Float
    ): Float {
        if (genuineScores.isEmpty() || backgroundScores.isEmpty()) return fallback
        val scores = FloatArray(genuineScores.size + backgroundScores.size)
        val labels = IntArray(scores.size)
        genuineScores.copyInto(scores, 0)
        backgroundScores.copyInto(scores, genuineScores.size)
        for (i in genuineScores.indices) labels[i] = 1

        val curve = rocCurve(scores, labels)
        val best = curve.minByOrNull { abs(it.far - it.frr) } ?: return fallback
        return best.threshold
    }

    /** Entropía cruzada binaria media; es el `loss` que espera Flower. */
    fun binaryCrossEntropy(scores: FloatArray, labels: IntArray): Double {
        if (scores.isEmpty()) return 0.0
        var total = 0.0
        for (i in scores.indices) {
            val p = scores[i].coerceIn(EPS, 1f - EPS).toDouble()
            total += if (labels[i] == 1) -ln(p) else -ln(1.0 - p)
        }
        return total / scores.size
    }

    /** Exactitud con un umbral dado. */
    fun accuracy(scores: FloatArray, labels: IntArray, threshold: Float): Double {
        if (scores.isEmpty()) return 0.0
        var hits = 0
        for (i in scores.indices) {
            val predicted = if (scores[i] >= threshold) 1 else 0
            if (predicted == labels[i]) hits++
        }
        return hits.toDouble() / scores.size
    }

    /** FAR y FRR con un umbral dado. */
    fun farFrr(scores: FloatArray, labels: IntArray, threshold: Float): Pair<Double, Double> {
        var falseAccepts = 0
        var negatives = 0
        var falseRejects = 0
        var positives = 0
        for (i in scores.indices) {
            if (labels[i] == 1) {
                positives++
                if (scores[i] < threshold) falseRejects++
            } else {
                negatives++
                if (scores[i] >= threshold) falseAccepts++
            }
        }
        val far = if (negatives == 0) 0.0 else falseAccepts.toDouble() / negatives
        val frr = if (positives == 0) 0.0 else falseRejects.toDouble() / positives
        return far to frr
    }

    private companion object {
        const val EPS = 1e-7f
    }
}
