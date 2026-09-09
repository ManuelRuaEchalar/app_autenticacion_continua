package com.example.autenticacioncontinua.sensor

import com.example.autenticacioncontinua.controlada.FuenteFalsa
import com.example.autenticacioncontinua.controlada.SesionesEnMemoria
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.sensor.CapturaInercial
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
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

    /**
     * @param config qué sensores registra. Por defecto [ConfiguracionSensores.D],
     *   los tres, que es lo que hacía la captura antes de que la configuración
     *   fuera conmutable y lo que asumen las pruebas de alineación de este
     *   fichero. Las que comprueban la conmutación pasan la suya.
     */
    private fun TestScope.captura(
        config: ConfiguracionSensores = ConfiguracionSensores.D
    ) = CapturaInercial(
        acelerometro = acelerometro,
        giroscopio = giroscopio,
        magnetometro = magnetometro,
        repositorio = repositorio,
        configuracion = { config },
        alcance = this
    )

    /**
     * Espera a que la captura se suscriba a los flujos.
     *
     * @param config a qué fuentes hay que esperar. Solo se comprueban las que la
     *   configuración registra: con [ConfiguracionSensores.A] el giroscopio
     *   nunca tiene colector, y exigirlo colgaría la espera hasta agotar los
     *   intentos y fallar por un motivo que no es el que la prueba mira.
     */
    private fun TestScope.esperarColector(
        config: ConfiguracionSensores = ConfiguracionSensores.D
    ) {
        val esperados = listOf(
            TipoSensor.ACELEROMETRO to acelerometro,
            TipoSensor.GIROSCOPIO to giroscopio,
            TipoSensor.MAGNETOMETRO to magnetometro
        ).filter { (tipo, _) -> config.requiere(tipo) }.map { it.second }

        repeat(20) {
            if (esperados.all { it.hayColector }) return
            runCurrent()
        }
        error("la captura no se suscribio a ${esperados.size} fuente(s) de ${config.clave}")
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

    // --- la configuracion decide QUE se registra --------------------------

    /**
     * El coste electrico de un sensor lo paga el aparato mientras esta
     * encendido, se procesen sus datos o no. Por eso la configuracion tiene que
     * decidir el REGISTRO y no un filtrado posterior: filtrar canales despues de
     * haberlos capturado mediria siempre el coste de la configuracion mas rica,
     * y el eje de recursos del estudio dejaria de distinguir un nivel de otro.
     */
    @Test
    fun `la configuracion A solo registra el acelerometro`() = runTest {
        val c = captura(ConfiguracionSensores.A)
        c.iniciar(bloqueId = 1)
        esperarColector(ConfiguracionSensores.A)

        assertTrue("el acelerometro tiene que arrancar", acelerometro.iniciada)
        assertFalse("el giroscopio no esta en la configuracion A", giroscopio.iniciada)
        assertFalse("el magnetometro tampoco", magnetometro.iniciada)

        c.detener()
        advanceUntilIdle()
    }

    @Test
    fun `la configuracion B no registra el magnetometro`() = runTest {
        val c = captura(ConfiguracionSensores.B)
        c.iniciar(bloqueId = 1)
        esperarColector(ConfiguracionSensores.B)

        assertTrue(acelerometro.iniciada && giroscopio.iniciada)
        assertFalse(magnetometro.iniciada)

        c.detener()
        advanceUntilIdle()
    }

    @Test
    fun `el tactil no anade ningun sensor inercial`() = runTest {
        // C es B mas un canal dirigido por eventos. Si registrara algo mas, la
        // prediccion de que B->C es mas barata que A->B perderia su fundamento.
        val c = captura(ConfiguracionSensores.C)
        c.iniciar(bloqueId = 1)
        esperarColector(ConfiguracionSensores.C)

        assertTrue(acelerometro.iniciada && giroscopio.iniciada)
        assertFalse(magnetometro.iniciada)

        c.detener()
        advanceUntilIdle()
    }

    /**
     * LA FUGA QUE ESTA PRUEBA EXISTE PARA IMPEDIR.
     *
     * La configuracion es estado de protocolo y el investigador la conmuta entre
     * bloques. Si `detener` recalculara las fuentes desde la configuracion del
     * momento en vez de recordar las que arranco, los sensores sobrantes
     * quedarian REGISTRADOS en el sensor manager tras detener la captura: sin
     * nadie consumiendo su flujo, pero despertando el aparato y gastando la
     * bateria que este modulo existe para medir. Y no fallaria nada: la captura
     * terminaria bien y el resumen saldria correcto.
     */
    @Test
    fun `para lo que arranco aunque la configuracion cambie a mitad`() = runTest {
        var activa = ConfiguracionSensores.D
        val c = CapturaInercial(
            acelerometro = acelerometro,
            giroscopio = giroscopio,
            magnetometro = magnetometro,
            repositorio = repositorio,
            configuracion = { activa },
            alcance = this
        )

        c.iniciar(bloqueId = 1)
        esperarColector()
        assertTrue("D arranca los tres", magnetometro.iniciada)

        // El investigador conmuta a B mientras el bloque corre.
        activa = ConfiguracionSensores.B

        c.detener()
        advanceUntilIdle()

        assertTrue(
            "el magnetometro se arranco con D y tiene que pararse aunque ahora " +
                "la configuracion sea B",
            magnetometro.detenida
        )
        assertTrue(acelerometro.detenida && giroscopio.detenida)
    }
}
