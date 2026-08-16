package com.example.autenticacioncontinua.domain.session

import android.util.Log
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.model.DailySessionStat
import com.example.autenticacioncontinua.domain.model.DeviceEventType
import com.example.autenticacioncontinua.domain.model.GyroscopeData
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Core business logic for IMU data collection sessions.
 *
 * Coordinates both gyroscope (50 Hz) and accelerometer (50 Hz) sensors.
 * Uses screen state events that work reliably from a foreground service.
 *
 * Ciclo:
 * 1. Se enciende / desbloquea la pantalla → se busca uso real.
 * 2. Ventana de 25 s con movimiento por encima del umbral → arranca la ráfaga.
 * 3. Ráfaga de 3 min → enfriamiento de 45 min → vuelta al paso 1.
 * 4. La pantalla se apaga → la ráfaga se corta y se guarda lo pendiente.
 * 5. Al llegar al presupuesto diario se para hasta el día siguiente.
 *
 * INVARIANTE. Ningún estado puede ser terminal para el proceso. Todos los
 * caminos de vuelta pasan por [onDeviceUnlocked], que es lo único que el
 * usuario dispara sin que se lo pidan, y allí las esperas se resuelven contra
 * el RELOJ DE PARED y no contra temporizadores. Los temporizadores de
 * corrutina se congelan con la CPU suspendida y no son una base fiable para
 * decidir si una espera ya venció.
 */
class SessionManagerImpl(
    private val gyroscopeSensor: IGyroscopeSensor,
    private val accelerometerSensor: IAccelerometerSensor,
    private val gyroscopeRepository: IGyroscopeRepository,
    private val accelerometerRepository: IAccelerometerRepository,
    private val deviceEvents: IDeviceEventRepository,
    private val labeledSessions: ILabeledSessionRepository
) : ISessionManager {

    companion object {
        private const val TAG = "SessionManager"

        /**
         * Pantalla encendida de forma continua antes de empezar a grabar.
         *
         * Estaba en 2 minutos, y esa cifra seleccionaba justo el uso que NO
         * interesa: quien mantiene la pantalla encendida 2 minutos seguidos
         * está viendo un vídeo o leyendo algo largo, con el teléfono quieto.
         * El uso interactivo real —mirar un aviso, contestar, buscar algo— son
         * ráfagas de 20-40 s que nunca alcanzaban el umbral y por tanto nunca
         * se grababan. Con 25 s entra ese uso y se sigue descartando el
         * encendido accidental de pantalla.
         */
        private const val REQUIRED_CONTINUOUS_USAGE_MS =
            CollectionPolicy.REQUIRED_CONTINUOUS_USAGE_MS

        /**
         * Duración máxima de una ráfaga.
         *
         * El presupuesto diario se agotaba de una sentada, así que la
         * grabación era UN bloque continuo al día y `WindowSegmenter`
         * detectaba UNA sola sesión —lo que hundía la partición por sesión de
         * `DatasetSplitter`—. Troceado en ráfagas cortas separadas por más de
         * `DEFAULT_SESSION_GAP_MS` (30 s), cada ráfaga es una sesión distinta.
         */
        private const val MAX_BOUT_MINUTES = CollectionPolicy.MAX_BOUT_MINUTES

        /** Separación entre ráfagas, para repartirlas a lo largo del día. */
        private const val COOLDOWN_MS = CollectionPolicy.COOLDOWN_MS

        /**
         * Presupuesto diario. Subido de 15 min porque el tope de 800 ventanas
         * de `WindowSegmenter` ya limita el conjunto de entrenamiento: grabar
         * más no lo agranda, agranda el POOL del que se muestrea diversidad de
         * sesiones, que es justo lo que faltaba.
         */
        private const val DAILY_LIMIT_MINUTES = CollectionPolicy.DAILY_LIMIT_MINUTES

        private const val BATCH_SIZE = 500

        /**
         * Energía de movimiento por debajo de la cual se considera que el
         * teléfono está apoyado, en m/s² (media de las desviaciones típicas
         * por eje, en un segundo).
         *
         * Derivado de la distribución de HMOG: sus percentiles p5-p10 de
         * `acc_std_medio` en unidades escaladas son 0.047-0.065, y la escala
         * media del StandardScaler para los tres ejes del acelerómetro es
         * 1.88, lo que da 0.089-0.123 m/s² en crudo. Se toma 0.10, en medio.
         * Es un gate, no el post-filtro: la versión precisa —canal a canal y
         * sobre ventanas ya normalizadas— vive en el filtrado de ventanas.
         */
        private const val MOTION_THRESHOLD_MS2 = 0.10

        /**
         * Fracción de la ventana que debe superar el umbral.
         *
         * Ajustado a 0.25 con medidas reales en el dispositivo:
         *   quieto en la mesa : fracción 0.00-0.04, energía media 0.011-0.013
         *   en la mano, en uso: fracción 0.46,      energía media 0.4632
         * Con el requisito en 0.40 el uso real pasaba por 0.06 de margen, y un
         * uso más pausado —sostener el móvil leyendo, que tiene muchos
         * segundos tranquilos— se habría quedado fuera. Bajarlo a 0.25 sigue
         * rechazando el reposo con holgura (harían falta 6 de 24 segundos por
         * encima del umbral) y recoge el uso suave.
         *
         * El error barato aquí es pasarse de permisivo: las ventanas quietas
         * que se cuelen las descarta después el post-filtro. Perder capturas
         * de uso legítimo no lo arregla nadie aguas abajo.
         */
        private const val MIN_ACTIVE_FRACTION = 0.25f

        /** Segundos mínimos medidos antes de decidir. */
        private const val MIN_SECONDS_SAMPLED = 10

        /**
         * Periodo de sondeo del enfriamiento.
         *
         * NO se espera el enfriamiento con un único `delay(COOLDOWN_MS)`.
         * `delay` se apoya en el reloj monótono, que no avanza mientras la CPU
         * está suspendida, y la app no toma ningún WakeLock: con la pantalla
         * apagada, 45 minutos de `delay` podían ser horas de reloj de pared.
         * Como `getCooldownRemainingMinutes()` sí mira el reloj de pared, la
         * pantalla anunciaba "la siguiente en breve" mientras el temporizador
         * seguía dormido y el estado no cambiaba nunca.
         *
         * Sondeando contra `System.currentTimeMillis()` el desfase máximo es
         * este periodo en vez de acumularse durante toda la espera.
         */
        private const val COOLDOWN_POLL_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var countdownJob: Job? = null

    /**
     * Ráfaga en curso: giroscopio y acelerómetro bajo un solo job.
     *
     * Antes eran dos jobs independientes y el del giroscopio hacía de reloj
     * para los dos. Al vencer el tiempo ejecutaba `return@collect`, que sale
     * de la lambda pero NO del `collect` —y un `collect` sobre un
     * `SharedFlow` no termina nunca—, así que el job quedaba `isActive` para
     * siempre y el guard de `startRecording` abortaba en silencio todas las
     * ráfagas posteriores del proceso.
     */
    private var recordingJob: Job? = null
    private var nextBoutJob: Job? = null

    @Volatile private var currentState = SessionState.IDLE
    @Volatile private var screenOn = false

    /** Resumen de la última medida de [hayUsoReal], para el diario. */
    @Volatile private var ultimaMedidaMovimiento = ""

    /** Instante (reloj de pared) a partir del cual se puede grabar otra vez. */
    @Volatile private var nextBoutAllowedAtMs = 0L

    /**
     * Día para el que se agotó el presupuesto, o null.
     *
     * El presupuesto es POR DÍA, pero `DAILY_LIMIT_REACHED` era un estado
     * terminal: nada volvía a mirar la fecha, así que el proceso dejaba de
     * recolectar también al día siguiente.
     */
    @Volatile private var dailyLimitDay: String? = null

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Punto de entrada de "el usuario está delante del teléfono".
     *
     * Cada estado que NO es de grabación tiene que poder salir de sí mismo
     * aquí, mirando el reloj de pared. Antes `COOLDOWN` y
     * `DAILY_LIMIT_REACHED` hacían `return` sin comprobar nada: eran trampas
     * de las que el proceso no salía nunca, y encender la pantalla —lo único
     * que el usuario puede hacer sin que se lo pidan— no las rescataba. De ahí
     * que hubiera que reiniciar el móvil para volver a recolectar.
     */
    override fun onDeviceUnlocked() {
        Log.d(TAG, "Device unlocked / screen on")
        screenOn = true
        if (currentState == SessionState.RECORDING) return
        // Una captura etiquetada manda sobre la recolección automática: si al
        // desbloquear se cayera a MONITORING_USAGE, `startCountdown` acabaría
        // arrancando una ráfaga normal encima de la del impostor y sus muestras
        // se guardarían como uso ambiental del dueño.
        if (currentState == SessionState.LABELED_CAPTURE) return

        if (currentState == SessionState.DAILY_LIMIT_REACHED) {
            if (dailyLimitDay == getCurrentDateString()) return
            Log.d(TAG, "Día nuevo: se reanuda la recolección")
            dailyLimitDay = null
            currentState = SessionState.IDLE
        }

        if (currentState == SessionState.COOLDOWN) {
            val restanteMs = nextBoutAllowedAtMs - System.currentTimeMillis()
            if (restanteMs > 0) {
                Log.d(TAG, "En enfriamiento (${getCooldownRemainingMinutes()} min); no se graba")
                return
            }
            // El temporizador de `scheduleNextBout` puede llevar dormido desde
            // que se apagó la pantalla. Aquí manda el reloj de pared.
            Log.d(TAG, "Enfriamiento vencido: se reanuda la búsqueda de uso")
            nextBoutJob?.cancel()
            currentState = SessionState.IDLE
        }

        currentState = SessionState.MONITORING_USAGE
        startCountdown()
    }

    override fun onScreenOff() {
        Log.d(TAG, "Screen off")
        screenOn = false
        countdownJob?.cancel()

        if (currentState == SessionState.RECORDING) {
            // No se marca enfriamiento: una ráfaga cortada por apagar la
            // pantalla es precisamente un episodio de uso corto, y que el
            // siguiente encendido pueda grabar otra vez es lo que multiplica
            // el número de sesiones distintas.
            stopRecording()
        }

        // Una captura etiquetada con la pantalla apagada es el teléfono sobre
        // la mesa, no una persona usándolo: se corta. El tramo queda cerrado
        // por el `finally` de la captura y la pantalla avisa al operador para
        // que la repita, que es preferible a quedarse con dos minutos de mesa
        // etiquetados como comportamiento de alguien.
        if (currentState == SessionState.LABELED_CAPTURE) {
            recordingJob?.cancel()
        }

        // COOLDOWN y DAILY_LIMIT_REACHED sobreviven al apagado de pantalla.
        // Ponerlos a IDLE aquí permitiría saltarse el enfriamiento sin más que
        // apagar y encender la pantalla, que es justo lo que se quiere evitar.
        if (currentState == SessionState.MONITORING_USAGE ||
            currentState == SessionState.RECORDING
        ) {
            currentState = SessionState.IDLE
        }
    }

    override fun startMonitoring() {
        Log.d(TAG, "Service started – monitoring screen state (gyro + accel @ 50 Hz)")
        currentState = SessionState.IDLE

        scope.launch {
            val today = getCurrentDateString()
            val stat = gyroscopeRepository.getDailySessionStat(today)
            if (stat != null && stat.totalMinutesRecorded >= DAILY_LIMIT_MINUTES) {
                // Sólo si nadie ha avanzado ya: `DataCollectionService.onCreate`
                // llama a `onDeviceUnlocked()` justo después de `startMonitoring()`
                // cuando la pantalla ya está encendida, y esta consulta a la BD
                // puede resolverse después.
                if (currentState == SessionState.IDLE ||
                    currentState == SessionState.MONITORING_USAGE
                ) {
                    countdownJob?.cancel()
                    dailyLimitDay = today
                    currentState = SessionState.DAILY_LIMIT_REACHED
                    Log.d(TAG, "Presupuesto de hoy ya agotado al arrancar el servicio")
                }
            }
        }
    }

    override fun stopMonitoring() {
        countdownJob?.cancel()
        nextBoutJob?.cancel()
        stopRecording()
        scope.cancel()
    }

    override fun getState(): SessionState = currentState

    override fun getCooldownRemainingMinutes(): Int {
        if (currentState != SessionState.COOLDOWN) return 0
        val restanteMs = nextBoutAllowedAtMs - System.currentTimeMillis()
        if (restanteMs <= 0) return 0
        // Se redondea hacia arriba: mostrar "0 min" mientras aún no puede
        // grabar sería la misma clase de mentira que se quiere corregir.
        return ((restanteMs + 59_999) / 60_000).toInt()
    }

    /**
     * Captura de laboratorio atribuida a una persona concreta.
     *
     * EL ORDEN IMPORTA: primero se anota la etiqueta en la base y sólo si eso
     * sale bien se enciende un sensor. Al revés —grabar y etiquetar después—
     * un fallo de escritura dejaría tres minutos de datos de otra persona
     * dentro del histórico del dueño, sin marca, indistinguibles y ya
     * mezclados con su material de entrenamiento. Por eso [abrir] propaga la
     * excepción en vez de tragársela como hace el diario.
     *
     * La fila se abre ANTES de la aclimatación a propósito: así el intervalo
     * excluido cubre también esos 30 s y ninguna muestra que se cuele por el
     * camino puede acabar contando como genuina.
     */
    override suspend fun startLabeledCapture(
        participantId: String,
        isOwner: Boolean,
        note: String,
        onFase: (LabeledCaptureFase) -> Unit
    ): Boolean {
        val id = participantId.trim()
        if (id.isBlank()) {
            onFase(LabeledCaptureFase.Fallida("Falta el identificador del participante"))
            return false
        }
        if (recordingJob?.isActive == true || currentState == SessionState.LABELED_CAPTURE) {
            onFase(
                LabeledCaptureFase.Fallida(
                    "Hay una grabación en curso. Espera a que termine."
                )
            )
            return false
        }

        // Que la recolección automática no arranque una ráfaga encima. El
        // estado pasa a LABELED_CAPTURE, que ninguna de las dos rutas
        // automáticas considera punto de partida válido.
        countdownJob?.cancel()
        nextBoutJob?.cancel()

        val filaId = try {
            labeledSessions.abrir(id, isOwner, note)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo anotar la sesión etiquetada; NO se graba nada", e)
            onFase(LabeledCaptureFase.Fallida("No se pudo anotar la etiqueta: ${e.message}"))
            return false
        }

        currentState = SessionState.LABELED_CAPTURE
        deviceEvents.record(
            DeviceEventType.LABELED_CAPTURE_STARTED,
            "$id${if (isOwner) " (control del dueño)" else " (impostor)"}" +
                if (note.isNotBlank()) " · $note" else ""
        )

        val duracionMs = CollectionPolicy.LABELED_BOUT_MINUTES * 60_000L
        var completada = false

        // LAZY para poder asignar `recordingJob` ANTES de que el cuerpo empiece
        // a correr: es el campo que mira el guard de `startRecording`, y con un
        // arranque inmediato hay una rendija en la que una ráfaga automática lo
        // vería todavía a null.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val inicioCaptura = System.currentTimeMillis()
            try {
                var restante = CollectionPolicy.acclimationSeconds
                while (restante > 0 && isActive) {
                    onFase(LabeledCaptureFase.Aclimatacion(restante))
                    delay(1_000)
                    restante--
                }
                if (!isActive) return@launch

                val t0 = System.currentTimeMillis()
                gyroscopeSensor.startListening()
                accelerometerSensor.startListening()

                val ticker = launch {
                    while (isActive) {
                        val queda = duracionMs - (System.currentTimeMillis() - t0)
                        if (queda <= 0) break
                        onFase(LabeledCaptureFase.Grabando(((queda + 999) / 1000).toInt()))
                        delay(1_000)
                    }
                }
                try {
                    capturarAmbosSensores(
                        maxMs = duracionMs,
                        dateStr = getCurrentDateString(),
                        startTime = t0
                    )
                } finally {
                    ticker.cancel()
                    stopAllSensors()
                }

                completada = true
                onFase(LabeledCaptureFase.Terminada(System.currentTimeMillis() - t0))
            } finally {
                withContext(NonCancellable) {
                    stopAllSensors()
                    // El tramo se cierra pase lo que pase. Un tramo abierto se
                    // excluye igual (se le supone la duración máxima), pero
                    // deja de saberse cuánto duró de verdad.
                    runCatching { labeledSessions.cerrar(filaId, System.currentTimeMillis()) }
                        .onFailure { Log.e(TAG, "No se pudo cerrar el tramo $filaId", it) }
                    deviceEvents.record(
                        DeviceEventType.LABELED_CAPTURE_FINISHED,
                        "$id · ${(System.currentTimeMillis() - inicioCaptura) / 1000} s" +
                            if (completada) "" else " · INTERRUMPIDA"
                    )
                }
                if (currentState == SessionState.LABELED_CAPTURE) {
                    currentState = SessionState.IDLE
                }
                if (!completada) {
                    onFase(
                        LabeledCaptureFase.Fallida(
                            "Captura interrumpida. Repítela: no apagues la pantalla " +
                                "ni salgas de la app mientras graba."
                        )
                    )
                }
            }
        }
        recordingJob = job
        job.start()
        job.join()
        return completada
    }

    override fun cancelLabeledCapture() {
        if (currentState != SessionState.LABELED_CAPTURE) return
        Log.d(TAG, "Captura etiquetada cancelada por el operador")
        recordingJob?.cancel()
    }

    // ── Private logic ────────────────────────────────────────────────────

    /**
     * Espera a que haya USO REAL, no sólo pantalla encendida.
     *
     * La version anterior solo esperaba `REQUIRED_CONTINUOUS_USAGE_MS` con la
     * pantalla encendida. Eso no mide interaccion: con el apagado automatico
     * de pantalla en 10 minutos (valor real medido en el dispositivo de
     * pruebas), la pantalla sigue encendida 10 min despues de cualquier toque,
     * asi que el criterio efectivo era "se encendio la pantalla en los ultimos
     * 10 minutos". Se grababa con el telefono quieto sobre la mesa.
     *
     * Android no expone eventos tactiles globales a una app normal —solo via
     * AccessibilityService, con permiso de ajustes y muy restringido por
     * politica de Play—, asi que el toque no es una senal disponible. Pero no
     * hace falta: un telefono en la mano tiene micro-movimiento constante
     * aunque no se toque, y uno en la mesa no. Se mide con el acelerometro,
     * que ya esta en la app y no exige ningun permiso nuevo.
     *
     * El bucle reintenta mientras la pantalla siga encendida: el usuario puede
     * coger el telefono en cualquier momento.
     */
    private fun startCountdown() {
        // Nunca se relanza uno sobre otro. Al desbloquear llegan DOS
        // broadcasts —ACTION_SCREEN_ON y ACTION_USER_PRESENT— y ambos acaban
        // aquí. Con el `cancel()` anterior, el job viejo seguía teniendo el
        // acelerómetro registrado y su `finally` ejecutaba `stopListening()`
        // DESPUÉS de que el job nuevo hubiera llamado a `startListening()`;
        // como el sensor es un singleton, ese `unregisterListener` mataba el
        // registro recién hecho y la ventana entera se iba en escuchar un flow
        // mudo ("Movimiento: 0/0 s activos").
        //
        // Relanzarlo no aportaba nada: el propio bucle ya reintenta mientras
        // la pantalla siga encendida.
        if (countdownJob?.isActive == true) return
        countdownJob = scope.launch {
            Log.d(
                TAG,
                "Esperando uso real: ventanas de " +
                    "${REQUIRED_CONTINUOUS_USAGE_MS / 1000}s con comprobación de movimiento"
            )
            while (isActive && screenOn && currentState == SessionState.MONITORING_USAGE) {
                if (hayUsoReal()) {
                    checkAndStartRecording()
                    return@launch
                }
                Log.d(TAG, "Pantalla encendida pero dispositivo quieto: no se graba")
                // Contar los rechazos es lo que dirá si MIN_ACTIVE_FRACTION
                // está bien puesto: muchos rechazos con el móvil claramente en
                // uso = umbral demasiado exigente, y eso hoy sólo se ve en un
                // logcat que a las tres horas ya no existe.
                deviceEvents.record(
                    DeviceEventType.GATE_REJECTED,
                    ultimaMedidaMovimiento
                )
            }
        }
    }

    /**
     * Mide movimiento durante una ventana y decide si el teléfono se está
     * usando.
     *
     * Estadístico: media de las desviaciones típicas por eje, el mismo que
     * usa el post-filtro de ventanas. Se usa POR EJE y no la magnitud |a|
     * porque una rotación pura —girar el móvil en la mano— cambia la
     * proyección de la gravedad en cada eje pero deja |a| en ~9,8: con la
     * magnitud, ese movimiento sería invisible.
     */
    private suspend fun hayUsoReal(): Boolean {
        var n = 0
        var sx = 0.0; var sx2 = 0.0
        var sy = 0.0; var sy2 = 0.0
        var sz = 0.0; var sz2 = 0.0
        var segundosActivos = 0
        var segundosTotales = 0
        // Se registran los valores medidos, no solo la decision: el umbral
        // salio de los percentiles de HMOG, y sin ver el margen real en este
        // dispositivo no hay forma de saber si esta bien puesto.
        var energiaMax = 0.0
        var energiaSuma = 0.0

        accelerometerSensor.startListening()
        try {
            withTimeoutOrNull(REQUIRED_CONTINUOUS_USAGE_MS) {
                var marca = System.currentTimeMillis()
                accelerometerSensor.getSensorDataFlow().buffer().collect { d ->
                    n++
                    sx += d.x; sx2 += d.x.toDouble() * d.x
                    sy += d.y; sy2 += d.y.toDouble() * d.y
                    sz += d.z; sz2 += d.z.toDouble() * d.z

                    if (System.currentTimeMillis() - marca >= 1_000L) {
                        if (n > 1) {
                            val energia = (desviacion(sx, sx2, n) +
                                desviacion(sy, sy2, n) +
                                desviacion(sz, sz2, n)) / 3.0
                            segundosTotales++
                            energiaSuma += energia
                            if (energia > energiaMax) energiaMax = energia
                            if (energia >= MOTION_THRESHOLD_MS2) segundosActivos++
                        }
                        n = 0
                        sx = 0.0; sx2 = 0.0; sy = 0.0; sy2 = 0.0; sz = 0.0; sz2 = 0.0
                        marca = System.currentTimeMillis()
                    }
                }
            }
        } finally {
            accelerometerSensor.stopListening()
        }

        val fraccion =
            if (segundosTotales > 0) segundosActivos.toFloat() / segundosTotales else 0f
        val energiaMedia = if (segundosTotales > 0) energiaSuma / segundosTotales else 0.0
        ultimaMedidaMovimiento =
            "$segundosActivos/$segundosTotales s activos " +
                "(fracción ${"%.2f".format(fraccion)}) | " +
                "energía media=${"%.4f".format(energiaMedia)} " +
                "max=${"%.4f".format(energiaMax)} " +
                "umbral=$MOTION_THRESHOLD_MS2 m/s²"
        Log.d(TAG, "Movimiento: $ultimaMedidaMovimiento")
        // Se exige un mínimo de segundos medidos para no decidir con dos
        // muestras si el sensor tardó en arrancar.
        return segundosTotales >= MIN_SECONDS_SAMPLED && fraccion >= MIN_ACTIVE_FRACTION
    }

    private fun desviacion(suma: Double, sumaCuadrados: Double, n: Int): Double {
        val media = suma / n
        val varianza = (sumaCuadrados / n) - media * media
        return if (varianza > 0) kotlin.math.sqrt(varianza) else 0.0
    }

    private suspend fun checkAndStartRecording() {
        val today = getCurrentDateString()
        val stat = gyroscopeRepository.getDailySessionStat(today) ?: DailySessionStat(today, 0)

        if (stat.totalMinutesRecorded >= DAILY_LIMIT_MINUTES) {
            dailyLimitDay = today
            currentState = SessionState.DAILY_LIMIT_REACHED
            Log.d(TAG, "Daily limit reached. Not recording.")
            return
        }

        val remainingMinutes = DAILY_LIMIT_MINUTES - stat.totalMinutesRecorded
        // Una ráfaga corta, no el presupuesto entero: es lo que hace que el
        // día produzca varias sesiones separadas en vez de un único bloque.
        val boutMinutes = minOf(remainingMinutes, MAX_BOUT_MINUTES)
        startRecording(stat, today, boutMinutes)
    }

    /**
     * Graba una ráfaga: los dos sensores bajo un único job con un único reloj.
     *
     * El tiempo lo lleva `withTimeoutOrNull` sobre los dos colectores a la vez,
     * no una de las dos corrutinas vigilando a la otra. Con el reparto
     * anterior, la del giroscopio cancelaba a la del acelerómetro, que sólo
     * conservaba su buffer porque comprobaba el plazo por su cuenta y llegaba
     * a volcarlo antes de que la cancelación se propagara. Medido sobre las 8
     * ráfagas grabadas: las dos señales acababan en el mismo instante y con la
     * misma cuenta de muestras, así que la carrera nunca llegó a perder nada,
     * pero dependía de un margen de milisegundos entre dos corrutinas. Con un
     * solo plazo para los dos no hay carrera que perder.
     *
     * El volcado va en un `finally` bajo `NonCancellable` para que también se
     * guarde lo pendiente cuando la ráfaga la corta `stopRecording()` al
     * apagarse la pantalla.
     */
    private fun startRecording(stat: DailySessionStat, todayStr: String, boutMinutes: Int) {
        if (recordingJob?.isActive == true) {
            // No debería pasar. Se registra porque la versión anterior salía
            // aquí en silencio, dejaba el estado en MONITORING_USAGE y el
            // proceso no volvía a grabar nunca sin dejar ni una línea de log.
            Log.w(TAG, "Ya hay una ráfaga en curso; no se inicia otra")
            return
        }

        currentState = SessionState.RECORDING
        Log.d(
            TAG,
            "▶ Ráfaga de $boutMinutes min (llevados " +
                "${stat.totalMinutesRecorded}/$DAILY_LIMIT_MINUTES min hoy)"
        )
        scope.launch {
            deviceEvents.record(
                DeviceEventType.BOUT_STARTED,
                "$boutMinutes min · llevados ${stat.totalMinutesRecorded}/" +
                    "$DAILY_LIMIT_MINUTES min hoy · $ultimaMedidaMovimiento"
            )
        }

        gyroscopeSensor.startListening()
        accelerometerSensor.startListening()

        val startTime = System.currentTimeMillis()
        val maxRecordingMs = boutMinutes * 60 * 1000L

        recordingJob = scope.launch {
            capturarAmbosSensores(
                maxMs = maxRecordingMs,
                dateStr = todayStr,
                startTime = startTime
            ) { elapsedMinutes ->
                val updated = DailySessionStat(
                    todayStr,
                    stat.totalMinutesRecorded + elapsedMinutes
                )
                gyroscopeRepository.updateDailySessionStat(updated)
                Log.d(
                    TAG,
                    "  Progreso: ${updated.totalMinutesRecorded}/$DAILY_LIMIT_MINUTES min"
                )
            }

            // Sólo se llega aquí si la ráfaga agotó su tiempo: los dos
            // `collect` son sobre SharedFlows y nunca completan por su cuenta,
            // así que `withTimeoutOrNull` sólo retorna al vencer el plazo. Si
            // la cortó `stopRecording()` al apagarse la pantalla, el job está
            // cancelado y no se llega a esta línea, con lo que NO se marca
            // enfriamiento: una ráfaga corta es justo un episodio de uso corto,
            // y que el siguiente encendido pueda grabar otra vez es lo que
            // multiplica el número de sesiones distintas.
            finishBout(stat, todayStr, boutMinutes)
        }
    }

    /**
     * Vuelca los dos sensores a la base durante [maxMs].
     *
     * Extraído para que la recolección automática y la captura etiquetada
     * compartan LA MISMA copia de la parte delicada, que es el `finally` bajo
     * `NonCancellable`: sin él, una ráfaga cortada a mitad —pantalla apagada,
     * captura cancelada— pierde hasta [BATCH_SIZE] muestras por sensor que ya
     * estaban en memoria. Duplicar este bloque era garantizar que una de las
     * dos copias acabase divergiendo.
     *
     * El plazo lo lleva un único `withTimeoutOrNull` sobre los dos colectores,
     * no una corrutina vigilando a la otra: así no hay carrera que perder al
     * cancelar (ver el comentario de [startRecording]).
     *
     * @param onMinuto se invoca al cruzar cada minuto completo, con los minutos
     *   transcurridos. La recolección automática lo usa para el contador del
     *   día; la captura etiquetada no lo necesita y pasa `null`.
     */
    private suspend fun capturarAmbosSensores(
        maxMs: Long,
        dateStr: String,
        startTime: Long,
        onMinuto: (suspend (Int) -> Unit)? = null
    ) {
        val gyroBuffer = mutableListOf<GyroscopeData>()
        val accelBuffer = mutableListOf<AccelerometerData>()

        try {
            withTimeoutOrNull(maxMs) {
                coroutineScope {
                    launch {
                        var lastSavedMinute = 0
                        gyroscopeSensor.getSensorDataFlow()
                            .buffer()
                            .collect { rawData ->
                                gyroBuffer.add(rawData.copy(dateString = dateStr))
                                if (gyroBuffer.size >= BATCH_SIZE) {
                                    gyroscopeRepository.saveGyroscopeData(gyroBuffer.toList())
                                    gyroBuffer.clear()
                                }

                                val elapsedMinutes =
                                    ((System.currentTimeMillis() - startTime) / 60_000).toInt()
                                if (elapsedMinutes > lastSavedMinute) {
                                    lastSavedMinute = elapsedMinutes
                                    onMinuto?.invoke(elapsedMinutes)
                                }
                            }
                    }
                    launch {
                        accelerometerSensor.getSensorDataFlow()
                            .buffer()
                            .collect { rawData ->
                                accelBuffer.add(rawData.copy(dateString = dateStr))
                                if (accelBuffer.size >= BATCH_SIZE) {
                                    accelerometerRepository
                                        .saveAccelerometerData(accelBuffer.toList())
                                    accelBuffer.clear()
                                }
                            }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (gyroBuffer.isNotEmpty()) {
                    gyroscopeRepository.saveGyroscopeData(gyroBuffer.toList())
                }
                if (accelBuffer.isNotEmpty()) {
                    accelerometerRepository.saveAccelerometerData(accelBuffer.toList())
                }
            }
        }
    }

    /**
     * Cierra una ráfaga: apaga sensores, consolida el contador del día y, si
     * queda presupuesto, programa la siguiente tras el enfriamiento.
     */
    private suspend fun finishBout(
        stat: DailySessionStat,
        todayStr: String,
        boutMinutes: Int
    ) {
        stopAllSensors()
        nextBoutAllowedAtMs = System.currentTimeMillis() + COOLDOWN_MS

        val total = stat.totalMinutesRecorded + boutMinutes
        gyroscopeRepository.updateDailySessionStat(DailySessionStat(todayStr, total))

        deviceEvents.record(
            DeviceEventType.BOUT_FINISHED,
            "$boutMinutes min grabados · $total/$DAILY_LIMIT_MINUTES min hoy"
        )

        if (total >= DAILY_LIMIT_MINUTES) {
            dailyLimitDay = todayStr
            currentState = SessionState.DAILY_LIMIT_REACHED
            Log.d(TAG, "✓ Presupuesto diario completo ($total/$DAILY_LIMIT_MINUTES min)")
            deviceEvents.record(
                DeviceEventType.DAILY_LIMIT,
                "$total/$DAILY_LIMIT_MINUTES min"
            )
        } else {
            currentState = SessionState.COOLDOWN
            Log.d(
                TAG,
                "⏸ Ráfaga terminada ($total/$DAILY_LIMIT_MINUTES min). " +
                    "Enfriamiento de ${COOLDOWN_MS / 60_000} min"
            )
            scheduleNextBout()
        }
    }

    /**
     * Programa la ráfaga siguiente para el caso en que el usuario siga con la
     * pantalla encendida cuando venza el enfriamiento.
     *
     * Es sólo la mitad del mecanismo, y la menos importante: este temporizador
     * se congela con la CPU suspendida. La que de verdad rescata el estado es
     * la comprobación de reloj de pared de `onDeviceUnlocked()`, y basta con
     * ella porque una ráfaga sólo puede empezar con la pantalla encendida —o
     * sea, con la CPU despierta—. Por eso no hace falta AlarmManager ni
     * WakeLock: no hay nada que despertar.
     */
    private fun scheduleNextBout() {
        nextBoutJob?.cancel()
        nextBoutJob = scope.launch {
            while (isActive && currentState == SessionState.COOLDOWN) {
                val restanteMs = nextBoutAllowedAtMs - System.currentTimeMillis()
                if (restanteMs <= 0) break
                delay(minOf(restanteMs, COOLDOWN_POLL_MS))
            }
            if (!isActive || currentState != SessionState.COOLDOWN) return@launch

            if (screenOn) {
                Log.d(TAG, "Enfriamiento cumplido y pantalla encendida: buscando uso real")
                currentState = SessionState.MONITORING_USAGE
                // Pasa por el gate de movimiento como cualquier otra ráfaga:
                // que haya vencido el enfriamiento no significa que el
                // teléfono se esté usando.
                startCountdown()
            } else {
                currentState = SessionState.IDLE
            }
        }
    }

    private fun stopAllSensors() {
        gyroscopeSensor.stopListening()
        accelerometerSensor.stopListening()
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        stopAllSensors()
        Log.d(TAG, "⏸ Recording stopped")
    }

    private fun getCurrentDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}
