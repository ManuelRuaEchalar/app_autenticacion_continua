package com.example.autenticacioncontinua.export

import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity
import com.example.autenticacioncontinua.domain.export.FormatoCsv
import com.example.autenticacioncontinua.domain.export.Manifiesto
import com.example.autenticacioncontinua.domain.export.TablasDeSesion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las tres pruebas que la fase 9 pide, sobre la parte que se puede probar en la
 * JVM: ida y vuelta fila a fila, huella estable, y sin nombres reales.
 */
class TablasDeSesionTest {

    private val sesion = SesionControladaEntity(
        id = 9,
        participanteId = 42,
        dispositivoId = "A",
        inicioMs = 1788232156981,
        finMs = 1788232467817,
        ordenDispositivo = 2,
        semillaSeleccion = -4473297490313019904L,
        versionApp = "1.13",
        versionProtocolo = "1.2",
        bateriaInicio = 51f,
        bateriaFin = 51f,
        estado = "COMPLETA"
    )

    private val bloques = listOf(
        BloqueEntity(
            id = 16, sesionId = 9, indice = 0, inicioMs = 1000, finMs = 101300,
            idioma = "es", parrafosUsados = "es_1194", pulsaciones = 225,
            errores = 23, borrados = 0, ppm = 24.6f, precision = 0.898f
        ),
        BloqueEntity(
            id = 17, sesionId = 9, indice = 1, inicioMs = 101300, finMs = 201500,
            idioma = "la", parrafosUsados = "la_0536,la_0537", pulsaciones = 197,
            errores = 27, borrados = 0, ppm = 20.5f, precision = 0.863f,
            interrumpido = true, motivoInterrupcion = "llamada entrante, se corto"
        )
    )

    /** Con los casos que rompen un CSV ingenuo: coma, comilla y nulos. */
    private val eventos = mapOf(
        16L to listOf(
            EventoTecleoEntity(
                bloqueId = 16, parrafoId = "es_1194", posicion = 0,
                esperado = ",", recibido = ",", acierto = true,
                tDownMs = 100, tUpMs = 255, x = 52.875f, y = 73.312f,
                presion = 1.0f, area = 2.0f
            ),
            EventoTecleoEntity(
                bloqueId = 16, parrafoId = "es_1194", posicion = 1,
                esperado = "\"", recibido = "a", acierto = false,
                tDownMs = 300, tUpMs = 460,
                // Coordenada descartada por venir de otra tecla, y presion que
                // este terminal no mide: los dos nulos que el corpus tiene.
                x = null, y = null, presion = null, area = 9.5f
            )
        ),
        17L to listOf(
            EventoTecleoEntity(
                bloqueId = 17, parrafoId = "la_0536", posicion = 0,
                esperado = " ", recibido = " ", acierto = true,
                tDownMs = 500, tUpMs = 646, x = 489.75f, y = 28.31f,
                presion = null, area = 1.5f
            )
        )
    )

    private val muestras = mapOf(
        16L to listOf(
            MuestraInercialEntity(
                bloqueId = 16, tParedMs = 1000, tMonotonoNs = 5_000_000_000,
                accX = 0.1f, accY = -9.64f, accZ = 0.03f,
                gyrX = 0.001f, gyrY = -0.002f, gyrZ = 0.11f,
                magX = 12.5f, magY = -3.25f, magZ = 40.0f
            ),
            // Sin magnetometro: el hueco que la sesion real trae en el 19% de
            // las muestras.
            MuestraInercialEntity(
                bloqueId = 16, tParedMs = 1010, tMonotonoNs = 5_010_000_000,
                accX = 0.2f, accY = -9.60f, accZ = 0.05f,
                gyrX = 0.003f, gyrY = -0.001f, gyrZ = 0.09f
            )
        ),
        17L to emptyList()
    )

    private fun tablas() = TablasDeSesion.de(sesion, "P07", bloques, eventos, muestras)

    // ------------------------------------------------------------------
    // 1. "el fichero exportado se vuelve a leer y coincide fila a fila"
    // ------------------------------------------------------------------

    @Test
    fun `las cuatro tablas salen con el numero de filas que les toca`() {
        val t = tablas()
        assertEquals(setOf("sesion.csv", "bloques.csv", "eventos_tecleo.csv",
            "muestras_inerciales.csv"), t.keys)
        assertEquals(1, FormatoCsv.leer(t.getValue("sesion.csv")).size)
        assertEquals(2, FormatoCsv.leer(t.getValue("bloques.csv")).size)
        assertEquals(3, FormatoCsv.leer(t.getValue("eventos_tecleo.csv")).size)
        assertEquals(2, FormatoCsv.leer(t.getValue("muestras_inerciales.csv")).size)
    }

    @Test
    fun `los eventos de tecleo se releen valor a valor`() {
        val csv = tablas().getValue("eventos_tecleo.csv")
        val cab = FormatoCsv.cabecera(csv)
        val filas = FormatoCsv.leer(csv)

        fun campo(fila: List<String?>, nombre: String) = fila[cab.indexOf(nombre)]

        // La coma tecleada sobrevive al viaje.
        assertEquals(",", campo(filas[0], "esperado"))
        assertEquals("52.875", campo(filas[0], "x"))
        assertEquals("1", campo(filas[0], "acierto"))
        assertEquals("155", campo(filas[0], "permanenciaMs"))

        // La comilla tambien.
        assertEquals("\"", campo(filas[1], "esperado"))
        assertEquals("0", campo(filas[1], "acierto"))

        // Y los nulos siguen siendo nulos, no ceros.
        assertNull("una coordenada descartada no puede volver como 0", campo(filas[1], "x"))
        assertNull(campo(filas[1], "y"))
        assertNull("presion no medida no es presion cero", campo(filas[1], "presion"))
        assertEquals("9.5", campo(filas[1], "area"))

        // El espacio es un caracter legitimo, no un vacio.
        assertEquals(" ", campo(filas[2], "esperado"))
    }

    @Test
    fun `el magnetometro ausente se relee como nulo`() {
        val csv = tablas().getValue("muestras_inerciales.csv")
        val cab = FormatoCsv.cabecera(csv)
        val filas = FormatoCsv.leer(csv)
        assertEquals("12.5", filas[0][cab.indexOf("magX")])
        assertNull("sin magnetometro es nulo, no cero", filas[1][cab.indexOf("magX")])
        assertEquals("el acelerometro nunca falta", "0.2", filas[1][cab.indexOf("accX")])
    }

    @Test
    fun `el motivo de interrupcion con comas sobrevive`() {
        val csv = tablas().getValue("bloques.csv")
        val cab = FormatoCsv.cabecera(csv)
        val filas = FormatoCsv.leer(csv)
        assertEquals("llamada entrante, se corto", filas[1][cab.indexOf("motivoInterrupcion")])
        assertEquals("1", filas[1][cab.indexOf("interrumpido")])
        assertEquals("la_0536,la_0537", filas[1][cab.indexOf("parrafosUsados")])
    }

    // ------------------------------------------------------------------
    // 2. "SHA-256 identico entre dispositivo y PC"
    // ------------------------------------------------------------------

    @Test
    fun `la huella es la de SHA-256, la misma que imprime sha256sum`() {
        // Vector conocido: SHA-256 de la cadena vacia.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Manifiesto.huella("")
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Manifiesto.huella("hello")
        )
    }

    @Test
    fun `la huella cambia si cambia un solo dato`() {
        val a = Manifiesto.huella(tablas().getValue("eventos_tecleo.csv"))
        val otros = eventos.toMutableMap().apply {
            this[16L] = listOf(eventos.getValue(16L)[0].copy(x = 52.876f)) + eventos.getValue(16L)[1]
        }
        val b = Manifiesto.huella(
            TablasDeSesion.de(sesion, "P07", bloques, otros, muestras)
                .getValue("eventos_tecleo.csv")
        )
        assertNotEquals("una milesima de pixel tiene que cambiar la huella", a, b)
    }

    @Test
    fun `el manifiesto firma las cuatro tablas y cuenta sus filas`() {
        val t = tablas()
        val json = Manifiesto.json(
            seudonimo = "P07", sesionId = 9, visita = 2, dispositivoId = "A",
            versionApp = "1.13", versionProtocolo = "1.2",
            exportadoMs = 1788232500000, tablas = t
        )
        for ((nombre, contenido) in t) {
            assertTrue("falta $nombre en el manifiesto", json.contains(nombre))
            assertTrue(
                "la huella de $nombre no cuadra",
                json.contains(Manifiesto.huella(contenido))
            )
        }
        assertTrue(json.contains("\"filas\": 3"))      // eventos_tecleo
        assertTrue(json.contains("\"formato\": \"sesion-controlada/1\""))
    }

    // ------------------------------------------------------------------
    // 3. "la exportacion no contiene el nombre real de nadie"
    // ------------------------------------------------------------------

    /**
     * EL IDENTIFICADOR INTERNO DEL PARTICIPANTE NO SALE. No es un numero
     * inocuo: es correlativo por orden de alta, o sea que revela EN QUE ORDEN
     * se recluto a la gente. Con veinte personas y una libreta de campo, eso
     * reidentifica.
     */
    @Test
    fun `no sale el identificador interno del participante, solo el seudonimo`() {
        val t = tablas()
        val csv = t.getValue("sesion.csv")
        assertTrue("el seudonimo si va", csv.contains("P07"))
        assertFalse(
            "participanteId no puede salir del telefono",
            FormatoCsv.cabecera(csv).contains("participanteId")
        )
        val fila = FormatoCsv.leer(csv).single()
        assertFalse("y su valor tampoco", fila.contains("42"))
    }

    /**
     * La tabla `participantes` NO se exporta entera, y la unica columna de
     * texto libre que existe es el seudonimo. Si alguien anadiera un campo de
     * nombre real, esta prueba no lo veria — pero es que el esquema no lo
     * tiene, y esta prueba fija que lo exportado se limita a lo enumerado.
     */
    @Test
    fun `las columnas exportadas son exactamente las declaradas`() {
        val t = tablas()
        assertEquals(
            listOf("sesionId", "seudonimo", "dispositivoId", "inicioMs", "finMs",
                "ordenDispositivo", "semillaSeleccion", "versionApp",
                "versionProtocolo", "bateriaInicio", "bateriaFin", "estado",
                "motivoInvalidacion"),
            FormatoCsv.cabecera(t.getValue("sesion.csv"))
        )
        assertEquals(
            listOf("bloqueId", "parrafoId", "posicion", "esperado", "recibido",
                "acierto", "borrado", "tDownMs", "tUpMs", "permanenciaMs",
                "x", "y", "presion", "area"),
            FormatoCsv.cabecera(t.getValue("eventos_tecleo.csv"))
        )
    }

    @Test
    fun `el nombre del paquete lleva seudonimo y visita, y limpia lo raro`() {
        assertEquals(
            "sesion_P07_v2_20260902-1015.zip",
            Manifiesto.nombreDelPaquete("P07", 2, "20260902-1015")
        )
        assertEquals(
            "sesion_P_07_v1_x.zip",
            Manifiesto.nombreDelPaquete("P/07", 1, "x")
        )
        assertEquals(
            "sesion_sin_id_v1_x.zip",
            Manifiesto.nombreDelPaquete("   ", 1, "x")
        )
    }
}
