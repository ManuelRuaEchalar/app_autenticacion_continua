package com.example.autenticacioncontinua.domain.tecleo

import kotlin.math.roundToInt

/**
 * Decide si las coordenadas de una pulsación pertenecen de verdad a su tecla.
 *
 * ### El fallo que motiva esta clase, medido el 31/08
 *
 * En la primera visita completa con dedos reales —MANUEL, visita 2, 657
 * pulsaciones— **23 de ellas (3.5%) traían coordenadas imposibles**: `x` de
 * −729 a +795 y `y` de −104 a +511, sobre teclas que miden 108 × 140 px.
 *
 * Los 23 casos coincidían EXACTAMENTE con las 23 pulsaciones solapadas —aquellas
 * cuyo `down` cae antes del `up` de la anterior, o sea los dos dedos abajo a la
 * vez— y los desplazamientos eran múltiplos de la distancia entre teclas. Cuando
 * hay dos punteros activos, el `MotionEvent` que llega a una tecla puede traer
 * las coordenadas referidas al sistema de la OTRA, y `getX(actionIndex)` las
 * devuelve tal cual.
 *
 * **Lo que NO estaba mal**, y por eso el fallo no daba ningún síntoma: el
 * carácter registrado era el correcto en los 23 casos (los 23 son aciertos), y
 * los tiempos de `down` y `up` también. Sólo mentía la posición. El resumen de
 * la sesión cuadraba, la precisión y las ppm eran correctas, y nada en la
 * pantalla ni en las pruebas podía delatarlo. Sólo apareció al mirar la
 * distribución de las coordenadas de una sesión real.
 *
 * ### Por qué se anula en vez de corregirse
 *
 * Porque no se puede corregir: no se sabe con qué desplazamiento vino el evento,
 * así que no hay forma de recuperar la posición verdadera. Lo que sí se puede es
 * no mentir. Un nulo significa «no medido» —igual que la presión en un terminal
 * que no la mide— y el análisis lo excluye limpiamente. Un valor falso de 500 px
 * de desviación, en cambio, contamina cualquier media o varianza de posición
 * dentro de la tecla, que es uno de los canales que el estudio registra.
 *
 * Vale la pena el matiz: se pierde el 3.5% de UN canal, y ese 3.5% no es
 * aleatorio —son las pulsaciones más rápidas, las de tecleo a dos manos—, así
 * que la pérdida está sesgada y hay que declararla. Aun así es preferible a
 * meter el sesgo dentro de los propios valores.
 */
object CoordenadaDeTecla {

    /**
     * Cuánto se tolera fuera del borde, en tanto por uno del lado de la tecla.
     *
     * NO ES CERO A PROPÓSITO. Un dedo que pulsa el borde izquierdo devuelve `x`
     * ligeramente negativo de forma perfectamente legítima —en la sesión medida
     * había valores de −4.8 px sobre teclas de 108— y esos toques son datos
     * buenos: pulsar sistemáticamente al borde es justo el rasgo que este canal
     * existe para captar.
     *
     * El 0.5 separa las dos poblaciones con holgura: lo legítimo se aleja unas
     * pocas decenas de píxeles, y la contaminación por multitáctil llega a
     * cientos, porque su magnitud es la distancia ENTRE teclas y ninguna tecla
     * está a media tecla de otra.
     */
    const val MARGEN = 0.5f

    /**
     * Si el punto cae dentro de la tecla, con el margen.
     *
     * Un tamaño de cero significa que el layout todavía no ha medido la tecla:
     * se acepta el punto en vez de descartarlo, porque en ese caso no hay nada
     * con lo que juzgarlo y descartar en masa la primera pulsación de cada
     * sesión sería peor que dejarla pasar.
     */
    fun dentro(x: Float, y: Float, ancho: Int, alto: Int): Boolean {
        if (ancho <= 0 || alto <= 0) return true
        val hx = ancho * MARGEN
        val hy = alto * MARGEN
        return x >= -hx && x <= ancho + hx && y >= -hy && y <= alto + hy
    }

    /**
     * La coordenada si es de fiar, o `null` si vino de otra tecla.
     *
     * Devuelve las dos juntas —y las anula juntas— porque una posición con la
     * `x` buena y la `y` de otra tecla no es media medida, es una medida falsa.
     */
    fun filtrar(
        x: Float?,
        y: Float?,
        ancho: Int,
        alto: Int
    ): Pair<Float?, Float?> {
        if (x == null || y == null) return null to null
        return if (dentro(x, y, ancho, alto)) x to y else null to null
    }

    /** Para poder decir en el diario cuánto se descartó, sin recalcularlo a mano. */
    fun porcentajeDescartado(descartadas: Int, total: Int): Int =
        if (total == 0) 0 else (100.0 * descartadas / total).roundToInt()
}
