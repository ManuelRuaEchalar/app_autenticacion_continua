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
    LABELED_CAPTURE
}
