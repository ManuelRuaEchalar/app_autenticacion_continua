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
     * @param aplicarFiltroActividad si descartar las ventanas sin movimiento
     *   suficiente, con el umbral que el propio dispositivo se calibra. Se
     *   pasa como parámetro —en vez de decidirlo dentro— porque el modo lo
     *   dicta el servidor, de forma que las dos corridas que se comparan usan
     *   el mismo APK y sólo cambia esto.
     *
     * @return lista posiblemente vacía; nunca `null`. Comprobar el tamaño
     *   contra el mínimo que exija cada caso de uso.
     */
    suspend fun getWindows(aplicarFiltroActividad: Boolean = true): List<SensorWindow>

    /**
     * Umbral de actividad que se autocalibró en la última llamada, o `null` si
     * no se llegó a filtrar.
     *
     * Se expone para poder anotarlo en el diario del dispositivo: en el móvil
     * de un participante al que no tenemos acceso, saber con qué umbral se
     * filtró su conjunto es la única forma de auditar después por qué salió
     * como salió.
     */
    val ultimoUmbralActividad: Float?
}
