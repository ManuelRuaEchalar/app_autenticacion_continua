package com.example.autenticacioncontinua.ml.metrics

import kotlin.math.abs

class EERCalculator {

    /**
     * Calculates the Equal Error Rate (EER) given a list of scores and true labels.
     * scores: List of Pair(score: Float, label: Int)
     * label = 1 for legitimate user, 0 for impostor
     */
    fun calculate(scores: List<Pair<Float, Int>>): Double {
        if (scores.isEmpty()) return 1.0
        
        val thresholds = (0..100).map { it / 100f }
        var minDiff = Double.MAX_VALUE
        var eer = 1.0
        
        val posScores = scores.filter { it.second == 1 }
        val negScores = scores.filter { it.second == 0 }
        
        if (posScores.isEmpty() || negScores.isEmpty()) {
            return 1.0 // Cannot calculate EER without both classes
        }

        for (threshold in thresholds) {
            // False Acceptance: impostor score >= threshold
            val falseAccepts = negScores.count { it.first >= threshold }
            val far = falseAccepts.toDouble() / negScores.size
            
            // False Rejection: legitimate score < threshold
            val falseRejects = posScores.count { it.first < threshold }
            val frr = falseRejects.toDouble() / posScores.size
            
            val diff = abs(far - frr)
            if (diff < minDiff) {
                minDiff = diff
                eer = (far + frr) / 2.0
            }
        }
        
        return eer
    }
}
