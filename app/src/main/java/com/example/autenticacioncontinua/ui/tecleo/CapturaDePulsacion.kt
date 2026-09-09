package com.example.autenticacioncontinua.ui.tecleo

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.IntSize
import com.example.autenticacioncontinua.domain.tecleo.CoordenadaDeTecla
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda

/**
 * Convierte los toques sobre una tecla en [PulsacionCruda].
 *
 * POR QUÉ `pointerInteropFilter` Y NO EL `pointerInput` NORMAL DE COMPOSE.
 * Porque hace falta el `MotionEvent` crudo. La API idiomática de Compose expone
 * `PointerInputChange.pressure`, pero **no expone el área de contacto**
 * (`getTouchMajor`), y el área es uno de los dos canales que la fase 3 tiene que
 * registrar: distingue el dedo que apoya la yema del que pica con la punta, y
 * es de los rasgos más estables de una persona. Sin el `MotionEvent` ese canal
 * no existe.
 *
 * SE REGISTRA `ACTION_CANCEL` COMO SOLTAR. Android cancela el gesto cuando algo
 * se lo lleva —un desplazamiento del contenedor, una notificación que roba el
 * foco—. Si no se tratara, esa pulsación se quedaría abierta para siempre y su
 * tiempo de permanencia se perdería. Se cierra con el instante de la
 * cancelación, que es cuando el dedo dejó de contar.
 *
 * LAS COORDENADAS SON RELATIVAS A LA TECLA, no a la pantalla. Es lo que hace
 * comparables dos participantes: importa si alguien pulsa sistemáticamente en
 * el borde inferior de la tecla, no en qué píxel de la pantalla cae.
 *
 * LAS COORDENADAS SE VALIDAN CONTRA EL TAMAÑO DE LA TECLA, y no es paranoia:
 * en la primera visita real con dedos, el 3.5% de las pulsaciones llegó con la
 * posición referida a OTRA tecla —siempre las solapadas, con dos dedos abajo a
 * la vez—. El carácter y los tiempos eran correctos; sólo mentía la posición.
 * Ver [CoordenadaDeTecla], que explica el hallazgo y por qué se anula en vez de
 * intentar corregirse.
 *
 * @param caracter lo que esta tecla escribe. Vacío en teclas de función.
 * @param esRetroceso si esta tecla borra.
 * @param tamanoTecla ancho y alto actuales de la tecla, en píxeles. Se pasa como
 *   función y no como valor porque el layout la mide DESPUÉS de componer el
 *   modificador: un valor congelado aquí sería siempre cero.
 * @param reloj inyectable para las pruebas; por defecto, reloj de pared, que es
 *   el que usan las demás tablas.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.capturaDePulsacion(
    caracter: String,
    esRetroceso: Boolean = false,
    tamanoTecla: () -> IntSize = { IntSize.Zero },
    reloj: () -> Long = { System.currentTimeMillis() },
    onPulsacion: (PulsacionCruda) -> Unit
): Modifier = this.pointerInteropFilter { evento ->
    val fase = when (evento.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> FaseDePulsacion.ABAJO
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_CANCEL -> FaseDePulsacion.ARRIBA
        else -> return@pointerInteropFilter false   // MOVE y demás no interesan
    }

    // `actionIndex` identifica CUÁL de los dedos generó este evento. Con
    // `getPressure(0)` a secas, un segundo dedo sobre otra tecla devolvería la
    // presión del primero: al teclear a dos manos, la mitad de las lecturas
    // serían del dedo equivocado.
    val i = evento.actionIndex

    // Se descartan JUNTAS si el punto no cae en esta tecla: con dos dedos
    // abajo, el evento puede traer la posición del otro, referida a su tecla.
    val tamano = tamanoTecla()
    val (x, y) = CoordenadaDeTecla.filtrar(
        x = runCatching { evento.getX(i) }.getOrNull(),
        y = runCatching { evento.getY(i) }.getOrNull(),
        ancho = tamano.width,
        alto = tamano.height
    )

    onPulsacion(
        PulsacionCruda(
            fase = fase,
            caracter = caracter,
            esRetroceso = esRetroceso,
            tMs = reloj(),
            x = x,
            y = y,
            // `takeIf { it > 0f }`: hay terminales que devuelven 0 cuando el
            // canal no existe, y 0 es un valor imposible para un dedo que está
            // tocando. Nulo dice "no medido"; cero diría "no hay presión".
            presion = runCatching { evento.getPressure(i) }.getOrNull()?.takeIf { it > 0f },
            area = runCatching { evento.getTouchMajor(i) }.getOrNull()?.takeIf { it > 0f }
        )
    )
    true
}
