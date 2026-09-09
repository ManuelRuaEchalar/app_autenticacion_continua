package com.example.autenticacioncontinua.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del régimen de visibilidad como factor de la medición.
 *
 * Lo que se cubre aquí no es la lectura de la API de Android —eso es un
 * envoltorio de dos líneas que necesita un teléfono— sino las dos reglas que sí
 * tienen consecuencia sobre el análisis: que un bloque cuyo estado cambia queda
 * marcado como tal en vez de fingir un valor, y que la resta contra la línea
 * base se niega a cruzar regímenes.
 *
 * La segunda es la que importa de verdad. Restar una línea base tomada con la
 * aplicación en primer plano a un bloque medido con la pantalla apagada
 * produciría un número que parece el coste de la actividad y que en realidad
 * contiene la diferencia entre tener la pantalla encendida y apagada —de otro
 * orden de magnitud que el efecto de añadir un sensor, y por tanto capaz de
 * invertir la conclusión del estudio.
 */
class EstadoPantallaTest {

    private fun muestra(
        t: Long,
        estado: EstadoPantalla,
        corriente: Long? = -250_000,
        pss: Long = 150_000
    ) = MuestraRecursos(
        tMs = t,
        cargaMicroAh = null,          // fuerza el método de integración
        corrienteMicroA = corriente,
        pssKb = pss,
        cargando = false,
        estadoPantalla = estado
    )

    // --- unanimidad o MIXTO -------------------------------------------------

    @Test
    fun `bloque con estado constante conserva ese estado`() {
        val r = ResumenRecursos.desde(
            "bloque", listOf(
                muestra(0, EstadoPantalla.PANTALLA_APAGADA),
                muestra(1_000, EstadoPantalla.PANTALLA_APAGADA),
                muestra(2_000, EstadoPantalla.PANTALLA_APAGADA)
            )
        )
        assertEquals(EstadoPantalla.PANTALLA_APAGADA, r.estadoPantalla)
    }

    @Test
    fun `bloque cuyo estado cambia a mitad queda MIXTO, no mayoritario`() {
        // Dos de tres muestras en primer plano: la moda diría PRIMER_PLANO y
        // sería mentira, porque el consumo del bloque es una mezcla.
        val r = ResumenRecursos.desde(
            "bloque", listOf(
                muestra(0, EstadoPantalla.PRIMER_PLANO),
                muestra(1_000, EstadoPantalla.PRIMER_PLANO),
                muestra(2_000, EstadoPantalla.PANTALLA_APAGADA)
            )
        )
        assertEquals(EstadoPantalla.MIXTO, r.estadoPantalla)
    }

    @Test
    fun `sin muestras de estado el bloque queda DESCONOCIDO y no MIXTO`() {
        // DESCONOCIDO es "no se pudo leer"; MIXTO es "cambió". No son lo mismo
        // y el análisis los trata distinto.
        assertEquals(EstadoPantalla.DESCONOCIDO, EstadoPantalla.deMuestras(emptyList()))
    }

    @Test
    fun `una muestra sin estado declarado vale DESCONOCIDO`() {
        val m = MuestraRecursos(
            tMs = 0, cargaMicroAh = null, corrienteMicroA = -1000,
            pssKb = 1000, cargando = false
        )
        assertEquals(EstadoPantalla.DESCONOCIDO, m.estadoPantalla)
    }

    // --- el monitor lo muestrea junto con lo demás ---------------------------

    @Test
    fun `el monitor sella cada muestra con el estado de ese instante`() {
        val monitor = MonitorBloque(
            energia = FuenteEnergiaFalsa(),
            memoria = FuenteMemoriaFalsa(),
            estadoPantalla = FuenteEstadoPantallaFalsa(listOf(EstadoPantalla.SEGUNDO_PLANO))
        )
        assertEquals(EstadoPantalla.SEGUNDO_PLANO, monitor.muestrear().estadoPantalla)
    }

    // --- la resta contra la línea base ---------------------------------------

    private fun resumen(
        etiqueta: String,
        estado: EstadoPantalla,
        corriente: Long
    ) = ResumenRecursos.desde(
        etiqueta, listOf(
            muestra(0, estado, corriente = corriente),
            muestra(1_800_000, estado, corriente = corriente)   // media hora
        )
    )

    @Test
    fun `resta el neto cuando los dos bloques comparten regimen`() {
        val bloque = resumen("inferencia", EstadoPantalla.PANTALLA_APAGADA, -400_000)
        val base = resumen("reposo", EstadoPantalla.PANTALLA_APAGADA, -100_000)

        val neto = ResumenRecursos.neto(bloque, base)

        assertNotNull("mismo método y mismo régimen: la resta es legítima", neto)
        // 400 mA - 100 mA = 300 mA sostenidos = 300 000 µAh por hora.
        assertEquals(300_000.0, neto!!, 1.0)
    }

    @Test
    fun `se niega a restar una linea base tomada en otro regimen`() {
        val bloque = resumen("inferencia", EstadoPantalla.PANTALLA_APAGADA, -400_000)
        val base = resumen("reposo", EstadoPantalla.PRIMER_PLANO, -100_000)

        assertNull(
            "restar entre regímenes mete la diferencia de pantalla en el efecto",
            ResumenRecursos.neto(bloque, base)
        )
    }

    @Test
    fun `un bloque MIXTO no sirve ni como medida ni como referencia`() {
        val mixto = ResumenRecursos.desde(
            "mixto", listOf(
                muestra(0, EstadoPantalla.PRIMER_PLANO, corriente = -400_000),
                muestra(1_800_000, EstadoPantalla.PANTALLA_APAGADA, corriente = -400_000)
            )
        )
        val base = resumen("reposo", EstadoPantalla.MIXTO, -100_000)

        assertNull("su consumo es una mezcla en proporción desconocida",
            ResumenRecursos.neto(mixto, base))
    }

    @Test
    fun `sigue negandose a restar entre metodos distintos aunque coincida el regimen`() {
        // La regla que ya existía no se ha debilitado al añadir la nueva.
        val porCorriente = resumen("inferencia", EstadoPantalla.PANTALLA_APAGADA, -400_000)
        val porContador = ResumenRecursos.desde(
            "reposo", listOf(
                MuestraRecursos(0, 3_000_000, null, 150_000, false,
                    EstadoPantalla.PANTALLA_APAGADA),
                MuestraRecursos(1_800_000, 2_900_000, null, 150_000, false,
                    EstadoPantalla.PANTALLA_APAGADA)
            )
        )

        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE, porCorriente.metodoConsumo)
        assertEquals(MetodoConsumo.CONTADOR_DE_CARGA, porContador.metodoConsumo)
        assertNull(ResumenRecursos.neto(porCorriente, porContador))
    }

    // --- el estado llega hasta la fila ---------------------------------------

    @Test
    fun `el estado del bloque viaja a la fila que se persiste`() {
        val r = resumen("inferencia", EstadoPantalla.SEGUNDO_PLANO, -400_000)

        val fila = com.example.autenticacioncontinua.data.local.entity
            .MedicionRecursosEntity.desde(r, "inferencia", "acc_gyro", "local")

        assertEquals("SEGUNDO_PLANO", fila.estadoPantalla)
    }

    @Test
    fun `la latencia se sella con el regimen que declara quien vuelca`() {
        val estadistica = EstadisticaLatencia.desde("inferencia_ventana", listOf(10.0, 12.0))

        val fila = com.example.autenticacioncontinua.data.local.entity
            .MedicionLatenciaEntity.desde(
                estadistica, "acc_gyro", "federado", EstadoPantalla.PANTALLA_APAGADA
            )

        assertEquals("PANTALLA_APAGADA", fila.estadoPantalla)
    }

    @Test
    fun `volcar sin declarar regimen deja DESCONOCIDO en vez de inventarlo`() {
        val estadistica = EstadisticaLatencia.desde("inferencia_ventana", listOf(10.0))

        val fila = com.example.autenticacioncontinua.data.local.entity
            .MedicionLatenciaEntity.desde(estadistica, "acc", "local")

        assertEquals("DESCONOCIDO", fila.estadoPantalla)
    }
}
