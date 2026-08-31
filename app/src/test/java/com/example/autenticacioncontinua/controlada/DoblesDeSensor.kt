package com.example.autenticacioncontinua.controlada

import com.example.autenticacioncontinua.domain.sensor.IFuenteSensor
import com.example.autenticacioncontinua.domain.sensor.MuestraSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fuente de sensor de mentira, con las muestras bajo control de la prueba.
 *
 * Las pruebas del estudio usan la `CapturaInercial` DE VERDAD sobre estas
 * fuentes, en vez de un doble de la captura entera. La razón es que lo que hay
 * que comprobar no es «se llamó a iniciar»: es que las muestras acaban en
 * `muestras_inerciales` atadas al bloque correcto, y eso pasa por el alineador
 * y por el escritor por lotes, que son justo donde algo se puede perder.
 *
 * `MutableSharedFlow` sin repetición y con capacidad: `CapturaInercial` se
 * suscribe cuando arranca su corrutina, y una prueba que emitiera antes de esa
 * suscripción perdería las muestras y fallaría por un motivo que no es el que
 * se está probando.
 */
class FuenteFalsa(
    override val tipo: TipoSensor,
    override val disponible: Boolean = true,
    override val hzSolicitados: Int = 100
) : IFuenteSensor {

    private val flujo = MutableSharedFlow<MuestraSensor>(
        replay = 0,
        extraBufferCapacity = 4_096
    )

    var iniciada = false
        private set
    var detenida = false
        private set

    override fun iniciar() { iniciada = true; detenida = false }
    override fun detener() { detenida = true }
    override fun flujo(): Flow<MuestraSensor> = flujo.asSharedFlow()

    /**
     * Si ya hay alguien escuchando.
     *
     * Un `SharedFlow` con `replay = 0` TIRA lo que se emite mientras no hay
     * suscriptores, y `tryEmit` devuelve `true` igualmente. La prueba tiene que
     * esperar a que la captura se haya suscrito antes de emitir, o mediria que
     * las muestras se pierden por su propia carrera y no por el codigo.
     */
    val hayColector: Boolean get() = flujo.subscriptionCount.value > 0

    /** Emite una muestra. Devuelve `false` si no cupo en el buffer. */
    fun emitir(tMonotonoNs: Long, x: Float = 0.1f, y: Float = 0.2f, z: Float = 9.8f): Boolean =
        flujo.tryEmit(
            MuestraSensor(
                tipo = tipo,
                x = x, y = y, z = z,
                tParedMs = tMonotonoNs / 1_000_000,
                tMonotonoNs = tMonotonoNs,
                precision = 3
            )
        )
}
