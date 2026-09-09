package com.example.autenticacioncontinua.ui.tecleo

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.example.autenticacioncontinua.R

/**
 * El chasquido y el golpecito que el teclado devuelve al pulsar una tecla.
 *
 * ### Por qué hace falta
 *
 * El teclado del estudio ya se iluminaba, pero un teclado real también SUENA, y
 * eso no es decoración: es lo que le dice al dedo que la tecla entró. Sin
 * respuesta, el participante mira la pantalla para confirmar cada pulsación, y
 * mirar la pantalla entre teclas es una conducta distinta de la que se quiere
 * medir —el tecleo se hace de reojo—. Es la otra mitad de reducir el coste
 * declarado de tener teclado propio, junto a la disposición calcada de MIUI.
 *
 * ### POR QUÉ NO SE USA `View.playSoundEffect`, QUE ERA LO OBVIO
 *
 * Porque **no habría sonado ni una vez en toda la recogida**, y no habría dado
 * ningún síntoma.
 *
 * `playSoundEffect` acaba en `AudioManager.playSoundEffect`, que reproduce por
 * `STREAM_SYSTEM`. Y el protocolo de sesión exige NO MOLESTAR ACTIVADO
 * (sección 7 y lista de verificación). Medido en el movil 1 el 31/08:
 *
 *     No molestar apagado    ->  ringer mode muted streams = 0x0
 *     No molestar activado   ->  ringer mode muted streams = 0x2 (STREAM_SYSTEM)
 *
 * Es decir: la propia condición que el protocolo impone silencia el canal por
 * el que iba a salir el chasquido. Las dos casillas de la lista de verificación
 * —«no molestar» y «sonido de toque»— eran incompatibles entre sí. Y el mismo
 * terminal tenía además `sound_effects_enabled = 0`, o sea que tampoco habría
 * sonado con No molestar apagado.
 *
 * Nada habría fallado. La sesión habría corrido entera, los datos habrían
 * cuadrado, y el participante habría tecleado sin respuesta sonora mientras el
 * documento decía que la tenía.
 *
 * ### Lo que se hace en su lugar, y por qué es mejor de todas formas
 *
 * Un [SoundPool] con `USAGE_MEDIA`, que sale por `STREAM_MUSIC` — el único que
 * No molestar no toca— y un clic propio empaquetado en `res/raw`.
 *
 * Lo que empezó siendo un rodeo resulta ser lo correcto para un instrumento de
 * medida: **el estímulo deja de depender del teléfono**. Con `playSoundEffect`,
 * que el participante oyera algo dependía de dos ajustes del aparato —los
 * efectos de toque y el volumen del sistema— que varían de móvil en móvil y que
 * el dueño puede cambiar entre visitas. Ahora el sonido es un fichero nuestro,
 * idéntico en los dos terminales y en todas las sesiones, y lo único que queda
 * del aparato es el volumen de multimedia, que la lista de verificación fija.
 *
 * El clic está SINTETIZADO y es reproducible: ruido pasa-banda a 3.2 kHz y
 * 1.1 kHz con caídas exponenciales de 3 y 7.5 ms sobre un cuerpo de 190 Hz,
 * 28 ms en total, semilla fija. No es una grabación con derechos ni el sonido
 * de un fabricante concreto.
 *
 * ### Por qué el sonido va por defecto y la vibración NO
 *
 * **El motor de vibración contamina el canal principal del estudio.** Durante
 * cada bloque se graban acelerómetro y giroscopio a 100 Hz, y el motor está
 * atornillado al mismo chasis que esos sensores: un golpecito por tecla mete en
 * la señal inercial un pulso muy por encima en amplitud del movimiento de la
 * mano, unas cuatrocientas veces por bloque. Y ese pulso tiene la FIRMA DEL
 * APARATO: los dos terminales son de fabricantes distintos —elegidos así a
 * propósito— y traen motores distintos. El modelo podría separar los dos
 * dispositivos por el eco de nuestro propio motor, que es exactamente lo que el
 * diseño cruzado persona × dispositivo existe para evitar. Sería un confound
 * fabricado por nosotros.
 *
 * El sonido no tiene ese problema: sale por el altavoz, no por el chasis.
 *
 * **Cambiarlo es una palabra:** [AMBAS] en [DEL_ESTUDIO]. Lo que no se puede es
 * cambiarlo a mitad de la recogida: la retroalimentación del teclado es parte
 * de la interfaz congelada (R3).
 */
enum class RetroalimentacionDeTecla(
    internal val sonido: Boolean,
    internal val vibracion: Boolean
) {
    /** Sólo el chasquido. Es el que usa el estudio; ver la nota de arriba. */
    SONIDO(sonido = true, vibracion = false),

    /** Sólo el golpecito. Contamina la señal inercial: ver la nota de arriba. */
    VIBRACION(sonido = false, vibracion = true),

    /** Como el teclado del sistema con todo activado. */
    AMBAS(sonido = true, vibracion = true),

    /** Nada. Se conserva para poder medir cuánto aporta la retroalimentación. */
    NINGUNA(sonido = false, vibracion = false);

    companion object {
        /**
         * LA CONSTANTE DEL PROTOCOLO. Es el único sitio donde se decide, y tiene
         * que ser la misma en los dos terminales durante toda la recogida.
         */
        val DEL_ESTUDIO = SONIDO
    }
}

/**
 * Reproductor del clic de tecla.
 *
 * SE CARGA UNA VEZ Y SE QUEDA EN MEMORIA. Es la razón de usar [SoundPool] y no
 * `MediaPlayer`: descomprime el clip al cargarlo y luego cada disparo es una
 * escritura en un búfer ya listo, sin abrir el fichero ni negociar el
 * decodificador. Con `MediaPlayer` la primera tecla de cada bloque habría
 * llegado tarde, y esa latencia sí se habría notado tecleando.
 *
 * [MAX_SIMULTANEOS] permite que un clic empiece antes de que el anterior se
 * apague: tecleando deprisa a dos manos las pulsaciones se solapan, y con un
 * solo canal cada tecla cortaría el sonido de la anterior — lo que se oiría
 * como pulsaciones perdidas justo cuando el participante va rápido.
 */
private class ReproductorDeClic(contexto: Context) {

    private var idClip = 0

    /**
     * Que el clip esté listo. Cargar es asíncrono, y disparar un id sin cargar
     * no falla: no hace nada, en silencio. Sin esta bandera, las primeras teclas
     * de la sesión sonarían o no según lo que hubiera tardado el disco.
     */
    @Volatile
    private var listo = false

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_SIMULTANEOS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // USAGE_MEDIA y no USAGE_ASSISTANCE_SONIFICATION: el segundo
                // sería el semánticamente correcto para un sonido de interfaz,
                // pero mapea a STREAM_SYSTEM, que es justo el que No molestar
                // silencia. Ver la nota de la clase.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    init {
        pool.setOnLoadCompleteListener { _, _, estado -> listo = estado == 0 }
        idClip = pool.load(contexto, R.raw.clic_tecla, 1)
    }

    fun clic() {
        if (!listo) return
        pool.play(idClip, VOLUMEN, VOLUMEN, PRIORIDAD, SIN_REPETIR, VELOCIDAD_NORMAL)
    }

    fun liberar() = pool.release()

    private companion object {
        const val MAX_SIMULTANEOS = 6
        /**
         * Volumen relativo al de multimedia. Fijo y al máximo: el nivel
         * absoluto lo fija el volumen del teléfono, que es lo que la lista de
         * verificación obliga a poner al valor del protocolo. Atenuar aquí
         * añadiría un segundo mando que nadie recordaría haber tocado.
         */
        const val VOLUMEN = 1.0f
        const val PRIORIDAD = 1
        const val SIN_REPETIR = 0
        const val VELOCIDAD_NORMAL = 1.0f
    }
}

/**
 * Devuelve la función que hay que llamar al bajar una tecla.
 *
 * SE DISPARA EN EL «ABAJO» Y ANTES DE REGISTRAR, como el teclado del sistema.
 * El instante de la pulsación ya se ha tomado dentro de [capturaDePulsacion]
 * —antes de que esta función llegue a existir en la cadena—, así que
 * adelantarla no toca ninguna medición y sí evita que el chasquido llegue
 * después del trabajo del ViewModel. En el «arriba» no suena nada: un teclado
 * que sonara dos veces por tecla no se parecería a ninguno.
 *
 * `SoundPool.play` y `performHapticFeedback` son ASÍNCRONAS: encolan y vuelven.
 * No bloquean el hilo de interfaz, de modo que no retrasan el `ACTION_UP` ni,
 * por tanto, el tiempo de permanencia, que es la magnitud más delicada de las
 * que se registran.
 *
 * EL REPRODUCTOR SE LIBERA AL SALIR de la pantalla. Un [SoundPool] retiene un
 * cliente del servidor de audio; dejarlo colgado en cada visita iría sumando
 * clientes en un proceso que está pensado para vivir días entre reinicios.
 */
@Composable
fun recordarRetroalimentacionDeTecla(
    modo: RetroalimentacionDeTecla = RetroalimentacionDeTecla.DEL_ESTUDIO
): () -> Unit {
    val contexto = LocalContext.current
    val vista = LocalView.current

    val reproductor = remember(modo, contexto) {
        if (modo.sonido) ReproductorDeClic(contexto) else null
    }

    DisposableEffect(reproductor) {
        onDispose { reproductor?.liberar() }
    }

    return remember(reproductor, vista, modo) {
        {
            reproductor?.clic()
            if (modo.vibracion) {
                // KEYBOARD_TAP es el patrón corto que Android reserva para las
                // teclas; el genérico VIRTUAL_KEY es más largo y se nota como
                // otra cosa. FLAG_IGNORE_VIEW_SETTING salta el ajuste de esta
                // vista concreta, no el del usuario.
                vista.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
        }
    }
}
