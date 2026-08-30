package com.example.autenticacioncontinua.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la agregación de recursos.
 *
 * Aquí está toda la lógica del módulo: las fuentes son envoltorios de dos
 * líneas sobre la API de Android y no tienen nada que probar sin un teléfono.
 * Estas pruebas cubren, sobre todo, los casos en los que una medición NO vale,
 * porque el fallo que invalidó las 676 mediciones anteriores fue exactamente
 * publicar un cero donde no había medida.
 */
class ResumenRecursosTest {

    private fun muestra(
        t: Long,
        carga: Long? = 3_000_000,
        corriente: Long? = -250_000,
        pss: Long = 150_000,
        cargando: Boolean = false
    ) = MuestraRecursos(t, carga, corriente, pss, cargando)

    @Test
    fun `calcula consumo, duracion y estadisticas de memoria`() {
        val r = ResumenRecursos.desde(
            "bloque", listOf(
                muestra(0, carga = 3_000_000, pss = 100_000),
                muestra(1_000, carga = 2_999_000, pss = 200_000),
                muestra(2_000, carga = 2_998_000, pss = 150_000)
            )
        )

        assertEquals(2_000L, r.duracionMs)
        assertEquals(3, r.nMuestras)
        assertEquals(2_000L, r.consumoMicroAh)          // 3 000 000 - 2 998 000
        assertEquals(100_000L, r.pssMinKb)
        assertEquals(200_000L, r.pssMaxKb)
        assertEquals(150_000.0, r.pssMedioKb, 0.001)
        assertTrue(r.esValida)
    }

    @Test
    fun `extrapola el consumo a una hora para poder comparar bloques desiguales`() {
        // 1 000 uAh en 2 s -> 1 800 000 uAh/h
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = 1_000_000),
                muestra(1_000, carga = 999_500),
                muestra(2_000, carga = 999_000)
            )
        )
        assertEquals(1_800_000.0, r.consumoMicroAhPorHora!!, 1.0)
    }

    @Test
    fun `marca invalida la medicion hecha con el cable puesto`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0), muestra(500), muestra(1_000, cargando = true)
            )
        )
        assertTrue(MotivoInvalidez.CARGANDO in r.invalidez)
        assertFalse(r.esValida)
    }

    @Test
    fun `si el contador no se mueve, no publica un cero y cae a la corriente`() {
        // Es lo que pasa en el Redmi del estudio: su contador de carga avanza en
        // escalones de 49 370 uAh (el 1% de la bateria), asi que un bloque de
        // minutos no lo mueve ni una vez. El consumo por contador sale 0, pero 0
        // NO es la medida — y la corriente si resuelve.
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = 3_000_000),
                muestra(500, carga = 3_000_000),
                muestra(1_000, carga = 3_000_000)
            )
        )
        assertEquals(0L, r.consumoMicroAh)
        assertTrue(MotivoInvalidez.CONTADOR_SIN_VARIACION in r.invalidez)
        // El motivo queda anotado, pero la medicion VALE por integracion.
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, r.metodoConsumo)
        assertTrue(r.esValida)
        // 250 000 uA durante 1 s = 69.44 uAh
        assertEquals(69.44, r.consumoIntegradoMicroAh!!, 0.01)
        assertEquals(250_000.0, r.tasaConsumoMicroAhPorHora!!, 1.0)
    }

    @Test
    fun `sin contador NI corriente no hay cifra de consumo, y no vale`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = null, corriente = null),
                muestra(500, carga = null, corriente = null),
                muestra(1_000, carga = null, corriente = null)
            )
        )
        assertEquals(MetodoConsumo.NINGUNO, r.metodoConsumo)
        assertNull(r.tasaConsumoMicroAhPorHora)
        assertFalse(r.esValida)
    }

    @Test
    fun `si el contador si se mueve, manda el contador y no la corriente`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = 3_000_000),
                muestra(1_000, carga = 2_999_000),
                muestra(2_000, carga = 2_998_000)
            )
        )
        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, r.metodoConsumo)
        assertEquals(3_600_000.0, r.tasaConsumoMicroAhPorHora!!, 1.0)
    }

    @Test
    fun `la integral pondera cada lectura por el intervalo que representa`() {
        // Muestreo IRREGULAR: 100 uA en los primeros 100 ms y 1 000 uA durante
        // los 1 900 ms siguientes. La media simple da 700; el trapecio tiene que
        // acercarse a 1 000, que es lo que de verdad gasto el bloque.
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = null, corriente = -100),
                muestra(100, carga = null, corriente = -1_000),
                muestra(2_000, carga = null, corriente = -1_000)
            )
        )
        val mediaEquivalente = r.consumoIntegradoMicroAh!! * 3_600_000.0 / r.duracionMs
        assertEquals(977.5, mediaEquivalente, 1.0)
        assertEquals("la media simple si estaria sesgada",
            700.0, r.corrienteMediaMicroA!!, 0.1)
    }

    @Test
    fun `no se puede restar una linea base medida con otro instrumento`() {
        val porCorriente = ResumenRecursos.desde(
            "trabajo", listOf(
                muestra(0, carga = 3_000_000),
                muestra(1_000, carga = 3_000_000),
                muestra(2_000, carga = 3_000_000)
            )
        )
        val porContador = ResumenRecursos.desde(
            "reposo", listOf(
                muestra(0, carga = 3_000_000),
                muestra(1_000, carga = 2_999_000),
                muestra(2_000, carga = 2_998_000)
            )
        )
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, porCorriente.metodoConsumo)
        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, porContador.metodoConsumo)
        assertNull(
            "restar tasas de instrumentos distintos daria un numero sin significado",
            ResumenRecursos.neto(porCorriente, porContador)
        )
    }

    @Test
    fun `una medicion con el cable puesto no vale aunque el contador se mueva`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = 3_000_000),
                muestra(1_000, carga = 2_999_000, cargando = true),
                muestra(2_000, carga = 2_998_000)
            )
        )
        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, r.metodoConsumo)
        assertFalse("con alimentacion externa la cifra es la del cargador", r.esValida)
    }

    @Test
    fun `sin contador de carga el consumo por contador es null, nunca cero`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = null),
                muestra(500, carga = null),
                muestra(1_000, carga = null)
            )
        )
        assertNull(r.consumoMicroAh)
        assertNull(r.consumoMicroAhPorHora)
        assertTrue(MotivoInvalidez.SIN_CONTADOR_DE_CARGA in r.invalidez)
        // Pero hay corriente, asi que la medicion se salva por integracion.
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, r.metodoConsumo)
        assertTrue(r.esValida)
    }

    @Test
    fun `con muy pocas muestras la medicion se marca insuficiente`() {
        val r = ResumenRecursos.desde("b", listOf(muestra(0), muestra(100, carga = 2_999_000)))
        assertTrue(MotivoInvalidez.MUESTRAS_INSUFICIENTES in r.invalidez)
    }

    @Test
    fun `la corriente se promedia en valor absoluto porque el signo no esta normalizado`() {
        // Hay fabricantes que devuelven negativo al descargar y otros positivo.
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, corriente = -200_000),
                muestra(500, corriente = 300_000),
                muestra(1_000, corriente = -400_000)
            )
        )
        assertEquals(300_000.0, r.corrienteMediaMicroA!!, 0.001)
    }

    @Test
    fun `usa los extremos temporales y no el maximo, que sesgaria al alza`() {
        // El contador del fabricante puede oscilar. Tomar max - min inflaria el
        // consumo; hay que tomar la primera y la ultima lectura.
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, carga = 3_000_000),
                muestra(500, carga = 3_010_000),   // oscilacion hacia arriba
                muestra(1_000, carga = 2_995_000)
            )
        )
        assertEquals(5_000L, r.consumoMicroAh)     // 3 000 000 - 2 995 000
    }

    @Test
    fun `ordena por tiempo aunque las muestras lleguen desordenadas`() {
        val r = ResumenRecursos.desde(
            "b", listOf(
                muestra(2_000, carga = 2_998_000),
                muestra(0, carga = 3_000_000),
                muestra(1_000, carga = 2_999_000)
            )
        )
        assertEquals(2_000L, r.duracionMs)
        assertEquals(2_000L, r.consumoMicroAh)
    }

    @Test
    fun `el consumo neto resta la linea base en tasa por hora`() {
        val bloque = ResumenRecursos.desde(
            "trabajo", listOf(
                muestra(0, carga = 1_000_000),
                muestra(1_000, carga = 999_000),
                muestra(2_000, carga = 998_000)
            )
        )
        val base = ResumenRecursos.desde(
            "reposo", listOf(
                muestra(0, carga = 1_000_000),
                muestra(1_000, carga = 999_750),
                muestra(2_000, carga = 999_500)
            )
        )
        // 3 600 000/h de trabajo menos 900 000/h de reposo
        assertEquals(2_700_000.0, ResumenRecursos.neto(bloque, base)!!, 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resumir sin muestras es un error de programacion, no un resultado vacio`() {
        ResumenRecursos.desde("b", emptyList())
    }
}
