package com.example.autenticacioncontinua.domain.session

interface ISessionManager {
    fun onDeviceUnlocked()
    fun onScreenOff()
    fun startMonitoring()
    fun stopMonitoring()
    fun getState(): SessionState

    /** Minutos que faltan para poder grabar otra ráfaga. 0 si no hay espera. */
    fun getCooldownRemainingMinutes(): Int

    /**
     * Graba una ráfaga atribuida explícitamente a [participantId].
     *
     * Es el modo de laboratorio del estudio: sirve para capturar a un impostor
     * en el MISMO teléfono que el usuario legítimo, que es la única condición
     * en la que la firma del aparato no puede explicar la separación entre las
     * dos clases.
     *
     * SE SALTA EL PRESUPUESTO DIARIO Y EL ENFRIAMIENTO a propósito. Los dos
     * existen para repartir la recolección automática a lo largo del día sin
     * gastar batería; una captura etiquetada la dispara el investigador, son
     * unas pocas por tarde, y si consumiera presupuesto, cuatro impostores
     * dejarían al dueño sin recolección ese día. Tampoco incrementa
     * `DailySessionStat`: esos minutos no son uso ambiental del dueño.
     *
     * Lo que sí respeta es la exclusividad: si hay una ráfaga automática en
     * curso, no se inicia y devuelve `false`.
     *
     * @param isOwner ráfaga de CONTROL del propio dueño, bajo el mismo
     *   protocolo que las de impostor. No se excluye del entrenamiento.
     * @param onFase notificación de progreso para la pantalla de captura.
     * @return `false` si no se pudo empezar (otra grabación en curso, o falló
     *   la anotación en la base — en cuyo caso NO se graba nada, porque unos
     *   datos sin etiqueta son peores que no tenerlos).
     */
    suspend fun startLabeledCapture(
        participantId: String,
        isOwner: Boolean,
        note: String,
        onFase: (LabeledCaptureFase) -> Unit
    ): Boolean

    /** Corta una captura etiquetada en curso, cerrando su tramo. */
    fun cancelLabeledCapture()

    /**
     * Suspende la recolección ambiental mientras dura una sesión controlada.
     *
     * ES LA ÚNICA INTERACCIÓN QUE LA RESTRICCIÓN R1 PERMITE entre el módulo del
     * estudio y la recogida ambiental. Durante una visita el teléfono lo tiene
     * un participante que NO es el dueño: si la recolección automática siguiera
     * corriendo, sus muestras caerían en `accelerometer_data` etiquetadas
     * implícitamente como uso del dueño, y el modelo personal se entrenaría con
     * datos de otra persona. El fallo sería silencioso.
     *
     * NO ES `stopMonitoring`. Ése cancela el `CoroutineScope` del gestor, y un
     * scope cancelado no se puede reutilizar: llamarlo y volver a arrancar
     * dejaría la recolección ambiental muerta hasta reiniciar el proceso. Esto
     * sólo para los temporizadores y cierra la ráfaga en curso, dejando al
     * gestor listo para [reanudar].
     *
     * Es idempotente: suspender dos veces no hace nada la segunda.
     */
    fun suspender()

    /**
     * Vuelve a la recolección ambiental normal.
     *
     * Si la pantalla está encendida, retoma la búsqueda de uso continuo en vez
     * de esperar al siguiente desbloqueo: al terminar una visita el
     * investigador tiene el teléfono en la mano y encendido, y quedarse en
     * IDLE hasta el siguiente apagado perdería ese tramo.
     */
    fun reanudar()

    val estaSuspendido: Boolean
}

/** Progreso de una captura etiquetada, para la pantalla que la dispara. */
sealed interface LabeledCaptureFase {
    /** Cuenta atrás de aclimatación. */
    data class Aclimatacion(val segundosRestantes: Int) : LabeledCaptureFase

    /** Grabando. */
    data class Grabando(val segundosRestantes: Int) : LabeledCaptureFase

    data class Terminada(val duracionMs: Long) : LabeledCaptureFase

    data class Fallida(val motivo: String) : LabeledCaptureFase
}

enum class SessionState {
    IDLE,
    MONITORING_USAGE,
    RECORDING,

    /**
     * Ráfaga terminada, esperando al enfriamiento antes de la siguiente.
     *
     * Sin este estado la UI caía al texto de IDLE y anunciaba que grabaría
     * "tras unos segundos de uso continuo" durante los 45 minutos de espera,
     * que es sencillamente falso.
     */
    COOLDOWN,

    DAILY_LIMIT_REACHED,

    /**
     * Captura etiquetada en curso (modo de laboratorio).
     *
     * Estado propio y no [RECORDING] porque la pantalla principal DEBE decir
     * algo distinto: si el dueño ve "Grabando datos IMU" mientras su cuñado
     * tiene el teléfono, no hay forma de notar que se está capturando en el
     * modo equivocado hasta que ya están los datos en la base.
     */
    LABELED_CAPTURE,

    /**
     * Recolección ambiental suspendida por una sesión controlada en curso.
     *
     * Estado propio por la misma razón que [LABELED_CAPTURE]: si la pantalla
     * principal dijera "Detectando uso continuo" mientras un participante hace
     * su visita, no habría forma de notar que la suspensión falló hasta ver los
     * datos. Aquí tiene que decir que está parada, y por qué.
     */
    SUSPENDIDA_POR_ESTUDIO
}
