package com.example.autenticacioncontinua.sensor

import com.example.autenticacioncontinua.controlada.FuenteFalsa
import com.example.autenticacioncontinua.controlada.SesionesEnMemoria
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.sensor.CapturaInercial
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cierre de la captura inercial.
 *
 * ### El fallo que estas pruebas fijan (30/08)
 *
 * El escritor acumula en un lote local y sólo toca la base al juntar
 * [MuestraInercialEntity.LOTE] filas. Hasta hoy, `detener()` cancelaba un único
 * `Job` que contenía al colector Y al escritor, de modo que el escritor moría
 * con su lote a medio llenar y esas filas se perdían.
 *
 * En un bloque real de 100 s a 100 Hz son ~10 000 muestras: veinte lotes
 * completos y un resto de hasta 499 que se iba en silencio — los últimos
 * segundos de tecleo de cada bloque, que no valen menos que los demás. Nada
 * fallaba, nada avisaba, y el recuento de `ResumenCaptura` decía la verdad sobre
 * lo escrito, así que ni siquiera cuadraba mal consigo mismo.
 *
 * Lo encontró una prueba de la fase 8 que esperaba 25 muestras y recibió 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturaInercialTest {

    private val acelerometro = FuenteFalsa(TipoSensor.ACELEROMETRO)
    private val giroscopio = FuenteFalsa(TipoSensor.GIROSCOPIO)
    private val magnetometro = FuenteFalsa(TipoSensor.MAGNETOMETRO)
    private val repositorio = SesionesEnMemoria()

    private fun TestScope.captura() = CapturaInercial(
        acelerometro = acelerometro,
        giroscopio = giroscopio,
        magnetometro = magnetometro,
        repositorio = repositorio,
        alcance = this
    )

    private fun TestScope.esperarColector() {
        repeat(20) {
            if (acelerometro.hayColector && giroscopio.hayColector) return
            runCurrent()
        }
        error("la captura no se suscribio")
    }

    private fun emitir(n: Int) {
        repeat(n) { i ->
            giroscopio.emitir(i * 10_000_000L)
            acelerometro.emitir(i * 10_000_000L)
        }
    }

    /**
     * MENOS DE UN LOTE ENTERO. Es el caso que se perdía por completo: sin llegar
     * a las 500 filas, el escritor no habia tocado la base ni una vez.
     */
    @Test
    fun `un lote incompleto se escribe igualmente al detener`() = runTest {
        val c = captura()
        c.iniciar(bloqueId = 7)
        esperarColector()

        emitir(25)
        advanceUntilIdle()
        val resumen = c.detener()
        advanceUntilIdle()

        assertEquals(25, repositorio.muestras.size)
        assertEquals(25L, resumen?.filasEscritas)
        assertTrue(repositorio.muestras.all { it.bloqueId == 7L })
    }

    /** Y el resto que sobra de los lotes completos tampoco se pierde. */
    @Test
    fun `el resto que no completa un lote tambien se escribe`() = runTest {
        val n = MuestraInercialEntity.LOTE + 37
        val c = captura()
        c.iniciar(bloqueId = 1)
        esperarColector()

        emitir(n)
        advanceUntilIdle()
        c.detener()
        advanceUntilIdle()

        assertEquals(n, repositorio.muestras.size)
    }

    @Test
    fun `detener para los sensores y deja de capturar`() = runTest {
        val c = captura()
        c.iniciar(bloqueId = 1)
        esperarColector()
        assertTrue(c.estaCapturando)

        c.detener()
        advanceUntilIdle()

        assertFalse(c.estaCapturando)
        assertTrue(acelerometro.detenida && giroscopio.detenida && magnetometro.detenida)
    }

    /**
     * El acelerometro sin giroscopio previo NO produce fila: un cero seria una
     * velocidad angular posible y caeria justo al principio del bloque.
     */
    @Test
    fun `las muestras sin giroscopio se descartan y se cuentan`() = runTest {
        val c = captura()
        c.iniciar(bloqueId = 1)
        esperarColector()

        acelerometro.emitir(0)
        acelerometro.emitir(10_000_000)
        advanceUntilIdle()
        val resumen = c.detener()
        advanceUntilIdle()

        assertEquals(0, repositorio.muestras.size)
        assertEquals(2L, resumen?.descartadasSinGiroscopio)
    }

    @Test
    fun `detener sin haber iniciado no revienta`() = runTest {
        assertEquals(null, captura().detener())
    }
}
