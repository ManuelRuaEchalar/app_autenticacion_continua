package com.example.autenticacioncontinua.domain.sensor

/**
 * La VARIABLE INDEPENDIENTE del estudio: qué sensores alimentan al modelo.
 *
 * POR QUÉ ES UN TIPO Y NO UNA CADENA. Hasta ahora la configuración viajaba como
 * el `String` que traía el manifiesto del modelo, fijo por compilación. Eso
 * bastaba mientras sólo existía una, pero el diseño exige recorrer varias
 * DENTRO de una misma campaña de medición, contrabalanceando el orden: si la
 * configuración A se midiera siempre con la batería al 90% y la C al 40%, la
 * diferencia entre ambas contendría el efecto del nivel de carga. Y con una
 * cadena suelta, un `"acc_gyro"` frente a un `"acc_giro"` partiría una celda
 * del diseño en dos sin que nada fallara.
 *
 * Aquí vive la ÚNICA definición de qué canales tiene cada nivel, cuántos son y
 * cómo se llama. El número de canales lo deriva la propia enumeración en vez de
 * escribirse a mano: `n_features` del manifiesto y los sensores que se registran
 * tienen que ser lo mismo, y si se declararan por separado acabarían
 * divergiendo.
 *
 * LOS NIVELES SON LOS DEL PERFIL APROBADO. A, B y C son las tres
 * configuraciones que el perfil compara. D es la respuesta a la segunda
 * observación del comité —explorar otros sensores— y se separa del resto porque
 * el magnetómetro tiene un problema propio: ver [ConfiguracionSensores.D].
 */
enum class ConfiguracionSensores(
    /** Clave estable. Va a cada fila de medición y agrupa el análisis. */
    val clave: String,
    /** Sensores inerciales que se registran. */
    val sensores: List<TipoSensor>,
    /** Si el nivel incorpora el canal táctil, que es dirigido por eventos. */
    val incluyeTactil: Boolean
) {

    /** Nivel A del perfil: sólo acelerómetro. */
    A("acc", listOf(TipoSensor.ACELEROMETRO), incluyeTactil = false),

    /** Nivel B: acelerómetro y giroscopio. Es la que está desplegada hoy. */
    B("acc_gyro", listOf(TipoSensor.ACELEROMETRO, TipoSensor.GIROSCOPIO), incluyeTactil = false),

    /**
     * Nivel C: B más los gestos táctiles.
     *
     * El táctil NO añade canales de sondeo continuo: es dirigido por eventos que
     * el sistema operativo ya genera de todos modos. Por eso [canalesInerciales]
     * vale lo mismo que en [B] y la diferencia de coste entre las dos debería
     * ser mucho menor que la que hay entre [A] y [B]. Esa asimetría es una
     * hipótesis contrastable del estudio, no una suposición de diseño.
     */
    C("acc_gyro_touch", listOf(TipoSensor.ACELEROMETRO, TipoSensor.GIROSCOPIO), incluyeTactil = true),

    /**
     * Nivel D: B más el magnetómetro. Sondeo continuo, tres canales más.
     *
     * DOS AVISOS QUE HAY QUE TENER DELANTE AL USARLA:
     *
     * 1. Es el sensor con MÁS firma de aparato. Su calibración de hierro duro y
     *    blando es propia de cada unidad, y además mide un campo ambiental
     *    propio de cada sitio; las dos cosas permanecen constantes durante el
     *    ataque real y por tanto valen cero en despliegue. El benchmark público
     *    del área lo midió: al pasar de impostores de otro aparato a impostores
     *    del mismo, el magnetómetro pierde 20 puntos de AUC frente a los 5 de
     *    las demás modalidades.
     * 2. En el terminal B del estudio TOPA EN 50 Hz. Es la razón por la que todo
     *    el corpus se captura a 50 Hz; ver `HZ_CONTROLADO`.
     */
    D("acc_gyro_mag", listOf(TipoSensor.ACELEROMETRO, TipoSensor.GIROSCOPIO, TipoSensor.MAGNETOMETRO), incluyeTactil = false);

    /** Tres ejes por sensor inercial. Es lo que espera el modelo como entrada. */
    val canalesInerciales: Int get() = sensores.size * EJES_POR_SENSOR

    /** Si esta configuración necesita registrar [tipo] en el sensor manager. */
    fun requiere(tipo: TipoSensor): Boolean = tipo in sensores

    companion object {
        const val EJES_POR_SENSOR = 3

        /** La que está desplegada y con la que se recogió todo lo anterior. */
        val POR_DEFECTO = B

        /**
         * Resuelve una clave a su configuración, o `null` si no existe.
         *
         * Devuelve `null` en vez de lanzar o de caer en un valor por defecto: el
         * llamador tiene que decidir qué hacer con una clave desconocida, y
         * elegir por él una configuración cualquiera contaminaría el análisis
         * con filas mal etiquetadas.
         */
        fun porClave(clave: String): ConfiguracionSensores? =
            entries.firstOrNull { it.clave == clave }
    }
}

/**
 * Quién dice qué configuración está activa AHORA.
 *
 * POR QUÉ UNA INTERFAZ Y NO UN VALOR INYECTADO. Porque el valor cambia durante
 * la ejecución, y quien lo consume —el medidor de recursos, la captura
 * inercial— no puede quedarse con una copia tomada al construirse. Con el valor
 * inyectado, cambiar de configuración a mitad de campaña dejaba a los
 * consumidores etiquetando con la anterior, y eso no falla: produce filas
 * plausibles y equivocadas.
 */
fun interface ProveedorDeConfiguracion {
    fun activa(): ConfiguracionSensores
}
