package com.example.autenticacioncontinua.monitoring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del protocolo de medición.
 *
 * Todo corre sobre el reloj virtual de `runTest`, así que un protocolo de tres
 * bloques de cinco minutos con enfriamiento se comprueba en milisegundos y sin
 * un solo `Thread.sleep`.
 *
 * Lo que se prueba son las decisiones de DISEÑO EXPERIMENTAL —orden
 * contrabalanceado, línea base por repetición, omisión por precondición—, que
 * es donde un fallo silencioso arruinaría los 60 días de recogida sin que nada
 * pareciera roto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtocoloDeBloquesTest {

    private class Montaje(
        scope: TestScope,
        val energia: FuenteEnergiaFalsa = FuenteEnergiaFalsa(),
        enfriamientoMs: Long = 0
    ) {
        val registro = RegistroEnMemoria()
        val medidor = MedidorDeOperacion(
            monitor = MonitorBloque(
                energia = energia,
                memoria = FuenteMemoriaFalsa(),
                periodoMuestreoMs = 100,
                alcance = scope,
                reloj = { scope.testScheduler.currentTime }
            ),
            cronometro = Cronometro(),
            registro = registro,
            configSensores = "acc_gyro",
            relojNanos = { scope.testScheduler.currentTime * 1_000_000 }
        )
        val protocolo = ProtocoloDeBloques(
            medidor = medidor,
            energia = energia,
            enfriamientoMs = enfriamientoMs,
            reloj = { scope.testScheduler.currentTime }
        )
    }

    private fun spec(nombre: String, duracionMs: Long = 1_000, pasoMs: Long = 100) =
        EspecificacionBloque(
            nombre = nombre,
            tipoOperacion = nombre,
            regimenAprendizaje = MedidorDeOperacion.REGIMEN_LOCAL,
            duracionMs = duracionMs,
            actividad = { delay(pasoMs) }
        )

    @Test
    fun `ejecuta cada bloque durante su duracion y registra las pasadas`() = runTest {
        val m = Montaje(this)
        val r = m.protocolo.ejecutar(listOf(spec("acc", duracionMs = 1_000, pasoMs = 100)))

        assertEquals(1, r.size)
        assertEquals(10, r.single().pasadas)
        assertNotNull(r.single().resumen)
        assertEquals(1, m.registro.bloques.size)
    }

    /**
     * La deriva del nivel de carga es el confound principal de una medición de
     * batería: si `acc` siempre va primero, va siempre con más carga que
     * `acc_gyro`, y la diferencia entre ambas contiene el efecto del nivel.
     */
    @Test
    fun `invierte el orden en las repeticiones pares para contrabalancear`() = runTest {
        val m = Montaje(this)
        val r = m.protocolo.ejecutar(
            listOf(spec("acc"), spec("acc_gyro"), spec("acc_gyro_mag")),
            repeticiones = 2
        )

        val nombres = r.map { it.especificacion.nombre }
        assertEquals(
            listOf(
                "acc", "acc_gyro", "acc_gyro_mag",
                "acc_gyro_mag", "acc_gyro", "acc"
            ),
            nombres
        )
    }

    @Test
    fun `con semilla el orden se baraja de forma reproducible`() = runTest {
        val especificaciones = listOf(spec("a"), spec("b"), spec("c"))
        val primera = Montaje(this).protocolo
            .ejecutar(especificaciones, repeticiones = 3, semilla = 7L)
            .map { it.especificacion.nombre }
        val segunda = Montaje(this).protocolo
            .ejecutar(especificaciones, repeticiones = 3, semilla = 7L)
            .map { it.especificacion.nombre }

        assertEquals(primera, segunda)
    }

    @Test
    fun `la linea base se mide una vez por repeticion, no una sola vez`() = runTest {
        val m = Montaje(this)
        val r = m.protocolo.ejecutar(
            listOf(spec("acc"), spec("acc_gyro")),
            repeticiones = 2,
            lineaBase = ProtocoloDeBloques.reposo(duracionMs = 500, periodoMs = 100)
        )

        val reposos = r.count { it.especificacion.tipoOperacion == MedidorDeOperacion.REPOSO }
        assertEquals(2, reposos)
        // Y va DELANTE de las condiciones de su repetición.
        assertEquals(MedidorDeOperacion.REPOSO, r.first().especificacion.tipoOperacion)
        assertEquals(MedidorDeOperacion.REPOSO, r[3].especificacion.tipoOperacion)
    }

    @Test
    fun `con el cable puesto no se mide y queda constancia del motivo`() = runTest {
        val m = Montaje(this, energia = FuenteEnergiaFalsa(cargando = true))
        val r = m.protocolo.ejecutar(listOf(spec("acc")))

        assertEquals(MotivoOmision.CARGANDO, r.single().omitidoPor)
        assertNull(r.single().resumen)
        assertEquals(0, r.single().pasadas)
        assertTrue("un bloque omitido no genera fila", m.registro.bloques.isEmpty())
    }

    @Test
    fun `fuera de la banda de bateria tampoco se mide`() = runTest {
        val m = Montaje(this, energia = FuenteEnergiaFalsa(porcentajeActual = 12f))
        val r = m.protocolo.ejecutar(listOf(spec("acc")))

        assertEquals(MotivoOmision.BATERIA_FUERA_DE_BANDA, r.single().omitidoPor)
    }

    /**
     * Un terminal que no expone porcentaje no debe quedarse sin medir: es
     * preferible medir y anotar la limitación que perder la sesión entera por
     * no poder comprobar una precondición.
     */
    @Test
    fun `sin porcentaje disponible se mide igualmente`() = runTest {
        val m = Montaje(this, energia = FuenteEnergiaFalsa(porcentajeActual = null))
        val r = m.protocolo.ejecutar(listOf(spec("acc")))

        assertNull(r.single().omitidoPor)
        assertNotNull(r.single().resumen)
    }

    @Test
    fun `un bloque que falla no tumba el resto del protocolo`() = runTest {
        val m = Montaje(this)
        val roto = EspecificacionBloque(
            nombre = "roto",
            tipoOperacion = "roto",
            regimenAprendizaje = MedidorDeOperacion.REGIMEN_LOCAL,
            duracionMs = 1_000,
            actividad = { throw IllegalStateException("el modelo no cargó") }
        )
        val r = m.protocolo.ejecutar(listOf(roto, spec("acc")))

        assertEquals(2, r.size)
        assertNotNull("el bloque roto guarda su error", r[0].error)
        assertEquals(0, r[0].pasadas)
        assertNull("el siguiente corre normalmente", r[1].error)
        assertEquals(10, r[1].pasadas)
    }

    @Test
    fun `el enfriamiento separa un bloque del siguiente`() = runTest {
        val m = Montaje(this, enfriamientoMs = 30_000)
        val t0 = testScheduler.currentTime
        m.protocolo.ejecutar(listOf(spec("a", duracionMs = 1_000), spec("b", duracionMs = 1_000)))
        val transcurrido = testScheduler.currentTime - t0

        // 2 s de bloques + 2 enfriamientos de 30 s.
        assertTrue("transcurrido=$transcurrido", transcurrido >= 62_000)
    }

    @Test
    fun `el consumo neto se empareja con la linea base de su propia repeticion`() = runTest {
        val m = Montaje(this)
        val r = m.protocolo.ejecutar(
            listOf(spec("acc", duracionMs = 1_000)),
            repeticiones = 2,
            lineaBase = ProtocoloDeBloques.reposo(duracionMs = 1_000, periodoMs = 100)
        )

        val neto = r.netoPorCondicion()
        assertEquals(setOf("acc"), neto.keys)
        assertEquals("una entrada por repetición", 2, neto.getValue("acc").size)
    }

    @Test
    fun `sin linea base valida no se inventa un neto`() = runTest {
        val m = Montaje(this)
        val r = m.protocolo.ejecutar(listOf(spec("acc")))   // sin reposo

        assertTrue(r.netoPorCondicion().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un protocolo sin bloques es un error de programacion`() = runTest {
        Montaje(this).protocolo.ejecutar(emptyList())
    }
}
