package com.example.autenticacioncontinua.monitoring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del coordinador de medición.
 *
 * Lo que se comprueba no es la aritmética —eso es de `ResumenRecursosTest`—
 * sino el PROTOCOLO: que el bloque se cierre pase lo que pase, que lo medido se
 * persista aunque la operación falle, y que las latencias se agrupen por clase
 * de operación y no por ejecución.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedidorDeOperacionTest {

    private class Montaje(val scope: TestScope) {
        val energia = FuenteEnergiaFalsa()
        val memoria = FuenteMemoriaFalsa(listOf(100_000L, 200_000L, 150_000L))
        val registro = RegistroEnMemoria()
        val cronometro = Cronometro()
        var nanos = 0L

        val monitor = MonitorBloque(
            energia = energia,
            memoria = memoria,
            periodoMuestreoMs = 100,
            alcance = scope,
            reloj = { scope.testScheduler.currentTime }
        )

        val medidor = MedidorDeOperacion(
            monitor = monitor,
            cronometro = cronometro,
            registro = registro,
            configSensores = { "acc_gyro" },
            relojNanos = { nanos }
        )
    }

    @Test
    fun `una operacion medida deja una fila con su configuracion y su regimen`() = runTest {
        val m = Montaje(this)
        val salida = m.medidor.medir("ronda_fl_1", MedidorDeOperacion.RONDA_FL, "federado") {
            advanceTimeBy(1_000)
            "listo"
        }

        assertEquals("listo", salida)
        assertEquals(1, m.registro.bloques.size)
        val fila = m.registro.bloques.single()
        assertEquals("ronda_fl_1", fila.etiqueta)
        assertEquals(MedidorDeOperacion.RONDA_FL, fila.tipoOperacion)
        assertEquals("acc_gyro", fila.configSensores)
        assertEquals("federado", fila.regimenAprendizaje)
    }

    /**
     * Es la garantía que evita la fuga: `MonitorBloque` deja una corrutina
     * muestreando cada 100 ms, y si una ronda que lanza no cerrara el bloque,
     * esa corrutina seguiría gastando batería para siempre.
     */
    @Test
    fun `si la operacion lanza, el bloque se cierra igual y queda registrado`() = runTest {
        val m = Montaje(this)
        var lanzada: Throwable? = null
        try {
            m.medidor.medir("ronda_fl_2", MedidorDeOperacion.RONDA_FL, "federado") {
                advanceTimeBy(500)
                throw IllegalStateException("el servidor cerró el canal")
            }
        } catch (e: Throwable) {
            lanzada = e
        }

        assertNotNull("la excepción debe seguir propagándose", lanzada)
        assertTrue(m.monitor.abiertos().isEmpty())
        assertEquals("la batería gastada hasta el fallo también es una medida",
            1, m.registro.bloques.size)
    }

    @Test
    fun `las latencias se agrupan por tipo de operacion y no por ejecucion`() = runTest {
        val m = Montaje(this)
        // Tres rondas: etiquetas distintas, misma clase de operación.
        for (ronda in 1..3) {
            m.nanos = 0
            m.medidor.medir("ronda_fl_$ronda", MedidorDeOperacion.RONDA_FL, "federado") {
                m.nanos = ronda * 10_000_000L      // 10, 20 y 30 ms
                advanceTimeBy(100)
            }
        }

        val resumen = m.cronometro.resumen(MedidorDeOperacion.RONDA_FL)
        assertNotNull("las tres rondas deben caer en la misma serie", resumen)
        assertEquals(3, resumen!!.n)
        assertEquals(20.0, resumen.medianaMs, 0.001)
        // Y NADA bajo la etiqueta de una ejecución concreta.
        assertNull(m.cronometro.resumen("ronda_fl_1"))
    }

    @Test
    fun `volcar latencias persiste un resumen por serie y vacia el cronometro`() = runTest {
        val m = Montaje(this)
        m.cronometro.registrarNanos(MedidorDeOperacion.RONDA_FL, 10_000_000)
        m.cronometro.registrarNanos(MedidorDeOperacion.RONDA_FL, 30_000_000)
        m.cronometro.registrarNanos(MedidorDeOperacion.INFERENCIA_VENTANA, 2_000_000)

        val series = m.medidor.volcarLatencias("federado")

        assertEquals(2, series)
        assertEquals(2, m.registro.latenciasGuardadas.size)
        val ronda = m.registro.latenciasGuardadas.first { it.etiqueta == MedidorDeOperacion.RONDA_FL }
        assertEquals(2, ronda.n)
        assertEquals(20.0, ronda.mediaMs, 0.001)
        // Vaciado: una segunda sesión no debe heredar las latencias de la primera.
        assertEquals(0, m.medidor.volcarLatencias("federado"))
    }

    @Test
    fun `un fallo al guardar no tumba la operacion medida`() = runTest {
        val m = Montaje(this)
        m.registro.fallaAlGuardar = true

        val salida = m.medidor.medir("x", MedidorDeOperacion.INFERENCIA, "local") {
            advanceTimeBy(200)
            41 + 1
        }

        assertEquals("medir no puede cambiar el resultado de lo medido", 42, salida)
    }

    @Test
    fun `el resumen que se devuelve es el mismo que se persiste`() = runTest {
        val m = Montaje(this)
        val medicion = m.medidor.medirConResumen("b", MedidorDeOperacion.REPOSO, "local") {
            advanceTimeBy(1_000)
        }

        val resumen = medicion.resumen!!
        val fila = m.registro.bloques.single()
        assertEquals(resumen.nMuestras, fila.nMuestras)
        assertEquals(resumen.duracionMs, fila.duracionMs)
        assertEquals(resumen.consumoMicroAh, fila.consumoMicroAh)
    }
}
