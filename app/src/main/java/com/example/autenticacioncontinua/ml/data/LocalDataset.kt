package com.example.autenticacioncontinua.ml.data

import com.example.autenticacioncontinua.domain.ml.SensorWindow
import kotlin.random.Random

/**
 * Partición local del usuario en tres conjuntos disjuntos POR SESIÓN.
 *
 * @property train ventanas genuinas con las que se entrena cada ronda.
 * @property validation lo que el cliente reporta al servidor en `evaluate`.
 *   Es la métrica con la que el servidor elige la mejor ronda y decide el
 *   early stopping.
 * @property test conjunto CIEGO. No se toca durante la federación; se reserva
 *   para la medición final que se reporta una sola vez.
 */
data class LocalDataset(
    val train: List<SensorWindow>,
    val validation: List<SensorWindow>,
    val test: List<SensorWindow>
) {
    val isUsable: Boolean get() = train.isNotEmpty() && validation.isNotEmpty()

    /**
     * Sesiones distintas en toda la partición.
     *
     * Es el dato que decide si las métricas son creíbles: por debajo de tres,
     * [DatasetSplitter] corta por tiempo dentro de una misma grabación y
     * train/val/test quedan contiguos. Viaja hasta el historial para que un
     * AUC alto nunca aparezca sin él al lado.
     */
    val sessionCount: Int
        get() = (train + validation + test).map { it.sessionId }.distinct().size

    fun summary(): String =
        "train=${train.size} val=${validation.size} test=${test.size} " +
            "(sesiones: ${train.sessions()}/${validation.sessions()}/${test.sessions()})"

    private fun List<SensorWindow>.sessions(): Int = map { it.sessionId }.distinct().size
}

/**
 * Corrección del sesgo metodológico detectado en la simulación.
 *
 * En `mejor.py`, `AuthClient.evaluate` calcula el AUC sobre `x_test`, ese AUC
 * alimenta `auc_ema`, y `auc_ema` decide qué ronda es la mejor y cuándo parar.
 * Después se reporta el EER final sobre ESE MISMO `x_test`. El conjunto de
 * test se usa a la vez para seleccionar modelo y para reportar, lo que empuja
 * el número reportado hacia arriba.
 *
 * Aquí cada dispositivo aparta un tercer subconjunto, también por sesión: el
 * servidor sólo ve `val_auc`, y `test` queda intacto durante toda la
 * federación.
 */
object DatasetSplitter {

    /**
     * @param testRatio fracción del total que va al conjunto ciego.
     * @param valRatio fracción DEL REMANENTE que va a validación.
     * @param seed semilla persistida: la partición debe ser idéntica en todas
     *   las rondas y sobrevivir a los reinicios de la app.
     */
    fun split(
        windows: List<SensorWindow>,
        testRatio: Float,
        valRatio: Float,
        seed: Long
    ): LocalDataset {
        if (windows.isEmpty()) return LocalDataset(emptyList(), emptyList(), emptyList())

        val (afterTest, test) = sessionSplit(windows, testRatio, Random(seed))
        // Semilla derivada para que el segundo corte no reproduzca el orden
        // del primero.
        val (train, validation) = sessionSplit(afterTest, valRatio, Random(seed xor VAL_SEED_MIX))
        return LocalDataset(train = train, validation = validation, test = test)
    }

    /**
     * Réplica de `_session_split` (mejor.py:405-431).
     *
     * Baraja las sesiones y va apartando sesiones enteras hasta cubrir la
     * fracción pedida. Nunca aparta todas: si sólo hay una sesión, corta por
     * tiempo dentro de ella y deja constancia de que esa partición es débil,
     * porque las ventanas de ambos lados del corte siguen siendo contiguas.
     *
     * @return (resto, apartado)
     */
    fun sessionSplit(
        windows: List<SensorWindow>,
        ratio: Float,
        random: Random
    ): Pair<List<SensorWindow>, List<SensorWindow>> {
        if (windows.isEmpty() || ratio <= 0f) return windows to emptyList()

        val sessions = windows.map { it.sessionId }.distinct()

        if (sessions.size == 1) {
            val nHoldout = (windows.size * ratio).toInt().coerceIn(0, windows.size - 1)
            if (nHoldout == 0) return windows to emptyList()
            val cut = windows.size - nHoldout
            return windows.subList(0, cut) to windows.subList(cut, windows.size)
        }

        val sizes = windows.groupingBy { it.sessionId }.eachCount()
        val order = sessions.shuffled(random)
        val target = windows.size * ratio

        // El resto tiene que seguir siendo utilizable.
        //
        // Sin esta cota, apartar sesiones enteras de forma codiciosa permite
        // que UNA sesión desproporcionada se lleve la partición completa:
        // medido con 478 ventanas en 2 sesiones (468 y 10), un objetivo de
        // test de 95 se cubría apartando la de 468 —que lo desborda 5 veces— y
        // dejaba train=8, val=2. Por debajo de un lote no se puede ni
        // entrenar. Se prefiere quedarse CORTO en el apartado antes que
        // vaciar el resto: un test pequeño se reporta con su tamaño al lado,
        // un train vacío no se arregla luego.
        val minRest = ((windows.size * (1f - ratio)) / 2f).toInt().coerceAtLeast(1)

        val holdoutSessions = LinkedHashSet<Int>()
        var accumulated = 0
        for (session in order) {
            if (accumulated >= target) break
            val size = sizes[session] ?: 0
            if (windows.size - (accumulated + size) < minRest) continue
            holdoutSessions.add(session)
            accumulated += size
        }
        // Nunca dejar el resto vacío.
        if (holdoutSessions.size == sessions.size) holdoutSessions.remove(order.last())

        val holdout = windows.filter { it.sessionId in holdoutSessions }
        val rest = windows.filter { it.sessionId !in holdoutSessions }
        return rest to holdout
    }

    private const val VAL_SEED_MIX = 0x5DEECE66DL
}
