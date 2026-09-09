package com.example.autenticacioncontinua.sensor

import com.example.autenticacioncontinua.data.controlada.advertirSiNoCasaConElModelo
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la configuración de sensores como variable independiente.
 *
 * Lo que se cubre aquí no es "el enum tiene los valores que tiene" —eso lo
 * garantiza el compilador— sino las tres propiedades de las que depende el
 * análisis: que las claves sean estables, que el número de canales se DERIVE de
 * los sensores en vez de escribirse aparte, y que una clave desconocida no se
 * resuelva silenciosamente a una configuración cualquiera.
 */
class ConfiguracionSensoresTest {

    @Test
    fun `las claves son las que agrupan el analisis y no pueden cambiar`() {
        // Si alguna de estas cambia, las filas ya recogidas dejan de agruparse
        // con las nuevas y una celda del diseño se parte en dos sin que nada
        // falle. Esta prueba existe para que ese cambio sea deliberado.
        assertEquals("acc", ConfiguracionSensores.A.clave)
        assertEquals("acc_gyro", ConfiguracionSensores.B.clave)
        assertEquals("acc_gyro_touch", ConfiguracionSensores.C.clave)
        assertEquals("acc_gyro_mag", ConfiguracionSensores.D.clave)
    }

    @Test
    fun `los canales se derivan de los sensores, no se declaran aparte`() {
        assertEquals(3, ConfiguracionSensores.A.canalesInerciales)
        assertEquals(6, ConfiguracionSensores.B.canalesInerciales)
        assertEquals(9, ConfiguracionSensores.D.canalesInerciales)
    }

    @Test
    fun `el tactil no anade canales inerciales`() {
        // Es el punto de la hipótesis de asimetría: C no es "B con un sensor
        // más de sondeo continuo", es B con un canal dirigido por eventos que
        // el sistema ya genera. Si esto dejara de cumplirse, la predicción de
        // que B->C es más barata que A->B perdería su fundamento.
        assertEquals(
            ConfiguracionSensores.B.canalesInerciales,
            ConfiguracionSensores.C.canalesInerciales
        )
        assertTrue(ConfiguracionSensores.C.incluyeTactil)
        assertFalse(ConfiguracionSensores.B.incluyeTactil)
    }

    @Test
    fun `cada configuracion pide exactamente sus sensores`() {
        assertTrue(ConfiguracionSensores.A.requiere(TipoSensor.ACELEROMETRO))
        assertFalse(ConfiguracionSensores.A.requiere(TipoSensor.GIROSCOPIO))
        assertFalse(ConfiguracionSensores.A.requiere(TipoSensor.MAGNETOMETRO))

        assertTrue(ConfiguracionSensores.B.requiere(TipoSensor.GIROSCOPIO))
        assertFalse(ConfiguracionSensores.B.requiere(TipoSensor.MAGNETOMETRO))

        assertTrue(ConfiguracionSensores.D.requiere(TipoSensor.MAGNETOMETRO))
    }

    @Test
    fun `el tactil no registra ningun sensor inercial de mas`() {
        assertEquals(ConfiguracionSensores.B.sensores, ConfiguracionSensores.C.sensores)
    }

    @Test
    fun `una clave desconocida devuelve null en vez de caer en una cualquiera`() {
        // Resolver silenciosamente a la de por defecto etiquetaría filas con una
        // configuración que no es la que se midió.
        assertNull(ConfiguracionSensores.porClave("acc_giro"))
        assertNull(ConfiguracionSensores.porClave(""))
        assertEquals(ConfiguracionSensores.B, ConfiguracionSensores.porClave("acc_gyro"))
    }

    @Test
    fun `todas las claves son distintas`() {
        val claves = ConfiguracionSensores.entries.map { it.clave }
        assertEquals(claves.size, claves.toSet().size)
    }

    @Test
    fun `la de por defecto es la desplegada`() {
        // El corpus recogido hasta ahora es de acc+gyro. Si la de por defecto
        // cambiara, un terminal que no haya fijado configuración empezaría a
        // etiquetar sus filas con otra cosa.
        assertEquals(ConfiguracionSensores.B, ConfiguracionSensores.POR_DEFECTO)
    }

    // --- aviso de desajuste con el modelo -----------------------------------

    @Test
    fun `avisa cuando la configuracion activa no casa con el modelo cargado`() {
        val avisos = mutableListOf<String>()
        val casa = advertirSiNoCasaConElModelo(
            activa = ConfiguracionSensores.D,
            claveDelModelo = "acc_gyro",
            log = { avisos += it }
        )
        assertFalse(casa)
        assertEquals(1, avisos.size)
        assertTrue(avisos.first().contains("RECURSOS"))
        assertTrue(avisos.first().contains("EFECTIVIDAD"))
    }

    @Test
    fun `no avisa cuando casan`() {
        val avisos = mutableListOf<String>()
        val casa = advertirSiNoCasaConElModelo(
            activa = ConfiguracionSensores.B,
            claveDelModelo = "acc_gyro",
            log = { avisos += it }
        )
        assertTrue(casa)
        assertTrue(avisos.isEmpty())
    }
}
