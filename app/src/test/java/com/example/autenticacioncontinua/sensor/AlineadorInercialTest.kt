package com.example.autenticacioncontinua.sensor

import com.example.autenticacioncontinua.data.sensor.AlineadorInercial
import com.example.autenticacioncontinua.domain.sensor.MuestraSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la fusión de las tres corrientes de sensor en una fila.
 *
 * Lo que se comprueba es que no se INVENTA nada: ni un cero donde no había
 * lectura de giroscopio, ni un valor de magnetómetro de hace un segundo
 * presentado como simultáneo.
 */
class AlineadorInercialTest {

    private val ms = 1_000_000L      // un milisegundo en nanosegundos

    private fun m(
        tipo: TipoSensor,
        tNs: Long,
        x: Float = 1f, y: Float = 2f, z: Float = 3f
    ) = MuestraSensor(
        tipo = tipo, x = x, y = y, z = z,
        tParedMs = tNs / 1_000_000, tMonotonoNs = tNs, precision = 3
    )

    @Test
    fun `el acelerometro manda y el giroscopio aporta su ultima lectura`() {
        val a = AlineadorInercial(bloqueId = 7)

        assertNull("una muestra de giroscopio no genera fila",
            a.aceptar(m(TipoSensor.GIROSCOPIO, 0, x = 10f, y = 11f, z = 12f)))

        val fila = a.aceptar(m(TipoSensor.ACELEROMETRO, 5 * ms))
        assertNotNull(fila)
        assertEquals(7L, fila!!.bloqueId)
        assertEquals(1f, fila.accX, 0f)
        assertEquals(10f, fila.gyrX, 0f)
        assertNull("sin magnetometro, va a nulo", fila.magX)
        assertEquals(5 * ms, fila.tMonotonoNs)
    }

    /**
     * Un cero de giroscopio es una velocidad angular perfectamente posible: no
     * se puede usar como "no había dato". Y esas muestras caen justo al
     * principio del bloque, que es donde el participante empieza a teclear.
     */
    @Test
    fun `descarta las muestras previas a la primera lectura de giroscopio`() {
        val a = AlineadorInercial(bloqueId = 1)

        assertNull(a.aceptar(m(TipoSensor.ACELEROMETRO, 0)))
        assertNull(a.aceptar(m(TipoSensor.ACELEROMETRO, 10 * ms)))
        assertEquals(2L, a.descartadasSinGiroscopio)
        assertEquals(0L, a.emitidas)

        a.aceptar(m(TipoSensor.GIROSCOPIO, 15 * ms))
        assertNotNull(a.aceptar(m(TipoSensor.ACELEROMETRO, 20 * ms)))
        assertEquals(1L, a.emitidas)
    }

    @Test
    fun `el magnetometro entra cuando esta fresco`() {
        val a = AlineadorInercial(bloqueId = 1)
        a.aceptar(m(TipoSensor.GIROSCOPIO, 0))
        a.aceptar(m(TipoSensor.MAGNETOMETRO, 0, x = 40f, y = -3f, z = 12f))

        val fila = a.aceptar(m(TipoSensor.ACELEROMETRO, 10 * ms))!!
        assertEquals(40f, fila.magX!!, 0f)
        assertEquals(-3f, fila.magY!!, 0f)
    }

    /**
     * Arrastrar una lectura de campo magnético de hace un segundo como si fuera
     * de ahora sería peor que no tenerla: el nulo dice la verdad.
     */
    @Test
    fun `un magnetometro rancio va a nulo en vez de arrastrarse`() {
        val a = AlineadorInercial(bloqueId = 1, desfaseMaximoNs = 25 * ms)
        a.aceptar(m(TipoSensor.GIROSCOPIO, 100 * ms))
        a.aceptar(m(TipoSensor.MAGNETOMETRO, 0, x = 40f))

        val fila = a.aceptar(m(TipoSensor.ACELEROMETRO, 100 * ms))!!
        assertNull(fila.magX)
    }

    /**
     * El giroscopio rancio SÍ se usa —descartar la fila entera perdería el
     * acelerómetro, que es la señal principal— pero se cuenta, para poder
     * reportar cuántas muestras no eran realmente simultáneas.
     */
    @Test
    fun `un giroscopio rancio se usa pero queda contado`() {
        val a = AlineadorInercial(bloqueId = 1, desfaseMaximoNs = 25 * ms)
        a.aceptar(m(TipoSensor.GIROSCOPIO, 0, x = 10f))

        val fresca = a.aceptar(m(TipoSensor.ACELEROMETRO, 20 * ms))!!
        assertEquals(10f, fresca.gyrX, 0f)
        assertEquals(0L, a.conGiroscopioRancio)

        val rancia = a.aceptar(m(TipoSensor.ACELEROMETRO, 200 * ms))!!
        assertEquals(10f, rancia.gyrX, 0f)
        assertEquals(1L, a.conGiroscopioRancio)
        assertEquals(2L, a.emitidas)
    }

    @Test
    fun `una corriente a 100 Hz produce una fila por muestra de acelerometro`() {
        val a = AlineadorInercial(bloqueId = 1)
        a.aceptar(m(TipoSensor.GIROSCOPIO, 0))
        a.aceptar(m(TipoSensor.MAGNETOMETRO, 0))

        var filas = 0
        for (i in 1..100) {
            // Giroscopio y magnetometro entrelazados, como llegan de verdad.
            a.aceptar(m(TipoSensor.GIROSCOPIO, i * 10 * ms - ms))
            if (i % 2 == 0) a.aceptar(m(TipoSensor.MAGNETOMETRO, i * 10 * ms - ms))
            if (a.aceptar(m(TipoSensor.ACELEROMETRO, i * 10 * ms)) != null) filas++
        }

        assertEquals(100, filas)
        assertEquals(0L, a.conGiroscopioRancio)
        assertEquals(0L, a.descartadasSinGiroscopio)
    }

    @Test
    fun `los valores de los tres sensores no se mezclan entre ejes`() {
        val a = AlineadorInercial(bloqueId = 1)
        a.aceptar(m(TipoSensor.GIROSCOPIO, 0, x = 0.1f, y = 0.2f, z = 0.3f))
        a.aceptar(m(TipoSensor.MAGNETOMETRO, 0, x = 40f, y = 50f, z = 60f))
        val f = a.aceptar(m(TipoSensor.ACELEROMETRO, 0, x = 9.8f, y = 0.5f, z = -0.2f))!!

        assertTrue(
            listOf(f.accX, f.accY, f.accZ) == listOf(9.8f, 0.5f, -0.2f) &&
                listOf(f.gyrX, f.gyrY, f.gyrZ) == listOf(0.1f, 0.2f, 0.3f) &&
                listOf(f.magX, f.magY, f.magZ) == listOf(40f, 50f, 60f)
        )
    }
}
