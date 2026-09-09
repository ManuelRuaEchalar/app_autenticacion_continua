package com.example.autenticacioncontinua.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El método de medida como POLÍTICA DEL ESTUDIO, no como elección por bloque.
 *
 * POR QUÉ EXISTE ESTA PRUEBA. Medido el 06/09 en los dos terminales: el
 * contador del Redmi 23129RA5FL avanza en escalones de 49 370 µAh y no resuelve
 * nada, mientras que el del Redmi Note 11 Pro lo hace en ~2 000 µAh y sí se
 * mueve en un bloque de 20 s bajo carga. Con la elección automática, el segundo
 * reportaría por contador y el primero por integración, y `neto` se negaría a
 * restar entre ellos: en un diseño cruzado persona × dispositivo eso vacía la
 * mitad de las celdas.
 *
 * Lo que se fija aquí es que forzar el método NO se limita a etiquetar la fila:
 * cambia qué cifra se reporta, y si el método exigido no se puede obtener, el
 * bloque se invalida en vez de caer en el otro.
 */
class PoliticaDeConsumoTest {

    /** Bloque de media hora con contador que sí avanza y corriente disponible. */
    private fun conAmbos() = listOf(
        MuestraRecursos(0, 3_000_000, -400_000, 150_000, false),
        MuestraRecursos(900_000, 2_950_000, -400_000, 150_000, false),
        MuestraRecursos(1_800_000, 2_900_000, -400_000, 150_000, false)
    )

    /**
     * Contador clavado: es el caso del terminal A.
     *
     * TRES muestras, no dos: por debajo de [ResumenRecursos.MIN_MUESTRAS] el
     * resumen sale invalido por MUESTRAS_INSUFICIENTES y la prueba comprobaria
     * otra cosa distinta de la que dice comprobar.
     */
    private fun soloCorriente() = listOf(
        MuestraRecursos(0, 3_000_000, -400_000, 150_000, false),
        MuestraRecursos(900_000, 3_000_000, -400_000, 150_000, false),
        MuestraRecursos(1_800_000, 3_000_000, -400_000, 150_000, false)
    )

    /** Sin corriente instantánea: el contador es la única vía. Ver [soloCorriente]. */
    private fun soloContador() = listOf(
        MuestraRecursos(0, 3_000_000, null, 150_000, false),
        MuestraRecursos(900_000, 2_950_000, null, 150_000, false),
        MuestraRecursos(1_800_000, 2_900_000, null, 150_000, false)
    )

    @Test
    fun `la politica del estudio es la integracion de corriente`() {
        // Si esto cambiara, dejarían de ser comparables las mediciones ya
        // recogidas con las nuevas.
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, MetodoConsumo.DEL_ESTUDIO)
    }

    @Test
    fun `sin exigir metodo, el contador gana cuando resuelve`() {
        val r = ResumenRecursos.desde("b", conAmbos())
        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, r.metodoConsumo)
    }

    @Test
    fun `exigir la integracion la impone aunque el contador resuelva`() {
        // ES EL CASO DEL TERMINAL B. Sin esto reportaría por contador y el A
        // por integración.
        val r = ResumenRecursos.desde("b", conAmbos(), MetodoConsumo.DEL_ESTUDIO)

        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, r.metodoConsumo)
        assertTrue(r.esValida)
        // Y la cifra reportable cambia de verdad: no es sólo la etiqueta.
        assertEquals(r.consumoIntegradoMicroAhPorHora, r.tasaConsumoMicroAhPorHora)
        assertNotNull(r.consumoMicroAh)   // el contador se conserva como dato
    }

    @Test
    fun `exigir la integracion no estorba cuando el contador no resuelve`() {
        // El terminal A ya caía aquí por su cuenta; forzarlo no cambia nada.
        val r = ResumenRecursos.desde("b", soloCorriente(), MetodoConsumo.DEL_ESTUDIO)

        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, r.metodoConsumo)
        assertTrue(r.esValida)
        assertTrue(MotivoInvalidez.CONTADOR_SIN_VARIACION in r.invalidez)
    }

    @Test
    fun `si el metodo exigido no se puede obtener, el bloque se invalida`() {
        // Sin corriente, el contador SÍ habría dado una cifra. Aceptarla
        // produciría un número comparable con los demás sólo en apariencia.
        val r = ResumenRecursos.desde("b", soloContador(), MetodoConsumo.DEL_ESTUDIO)

        assertEquals(MetodoConsumo.NINGUNO, r.metodoConsumo)
        assertNull(r.tasaConsumoMicroAhPorHora)
        assertFalse(r.esValida)
        assertTrue(MotivoInvalidez.SIN_METODO_EXIGIDO in r.invalidez)
        // Y se invalida POR POLITICA, no por quedarse corto de muestras: si
        // fuera por eso, la prueba pasaria sin comprobar nada de lo suyo.
        assertFalse(MotivoInvalidez.MUESTRAS_INSUFICIENTES in r.invalidez)
    }

    @Test
    fun `el motivo distingue politica de falta de instrumento`() {
        // Sin contador y sin corriente no es un descarte por política: no había
        // nada que medir. El análisis tiene que poder separar los dos casos.
        val sinNada = listOf(
            MuestraRecursos(0, null, null, 150_000, false),
            MuestraRecursos(900_000, null, null, 150_000, false),
            MuestraRecursos(1_800_000, null, null, 150_000, false)
        )
        val r = ResumenRecursos.desde("b", sinNada, MetodoConsumo.DEL_ESTUDIO)

        assertTrue(MotivoInvalidez.SIN_CONTADOR_DE_CARGA in r.invalidez)
        assertTrue(MotivoInvalidez.SIN_CORRIENTE in r.invalidez)
        assertTrue(MotivoInvalidez.SIN_METODO_EXIGIDO in r.invalidez)
        assertFalse(r.esValida)
    }

    @Test
    fun `con la politica los dos terminales quedan restables entre si`() {
        // La razón de todo esto: que `neto` funcione entre un bloque medido en
        // un terminal y una línea base medida en el otro régimen de contador.
        val comoElB = ResumenRecursos.desde("carga", conAmbos(), MetodoConsumo.DEL_ESTUDIO)
        val comoElA = ResumenRecursos.desde("reposo", soloCorriente(), MetodoConsumo.DEL_ESTUDIO)

        assertEquals(comoElB.metodoConsumo, comoElA.metodoConsumo)
        assertNotNull(
            "con la política, la resta es legítima entre terminales",
            ResumenRecursos.neto(comoElB, comoElA)
        )
    }

    @Test
    fun `sin la politica esos mismos dos bloques no se pueden restar`() {
        // El contrafactual: es exactamente lo que pasaría hoy sin fijar el método.
        val comoElB = ResumenRecursos.desde("carga", conAmbos())
        val comoElA = ResumenRecursos.desde("reposo", soloCorriente())

        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, comoElB.metodoConsumo)
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, comoElA.metodoConsumo)
        assertNull(ResumenRecursos.neto(comoElB, comoElA))
    }

    @Test
    fun `el monitor aplica la politica del estudio por defecto`() {
        val monitor = MonitorBloque(
            energia = FuenteEnergiaFalsa(descargaPorLectura = 50_000),
            memoria = FuenteMemoriaFalsa()
        )
        monitor.iniciar("b")
        val r = monitor.detener("b")

        assertNotNull(r)
        assertTrue(
            "por defecto no puede salir por contador",
            r!!.metodoConsumo != MetodoConsumo.CONTADOR_DE_CARGA
        )
    }
}
