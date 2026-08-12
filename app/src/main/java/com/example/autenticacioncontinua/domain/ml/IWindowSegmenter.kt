package com.example.autenticacioncontinua.domain.ml

interface IWindowSegmenter {

    /**
     * Construye las ventanas normalizadas disponibles en la base local.
     *
     * Reproduce el pipeline de `load_hmog` de `mejor.py`: segmenta el
     * histórico en sesiones de uso continuo, remuestrea cada sesión a una
     * rejilla uniforme de `target_hz` por interpolación lineal y recorta
     * ventanas solapadas de `windowSize` con paso `windowStep`.
     *
     * @return lista posiblemente vacía; nunca `null`. Comprobar el tamaño
     *   contra el mínimo que exija cada caso de uso.
     */
    suspend fun getWindows(): List<SensorWindow>
}
