package com.example.autenticacioncontinua.data

import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity
import com.example.autenticacioncontinua.monitoring.EstadisticaLatencia
import com.example.autenticacioncontinua.monitoring.MetodoConsumo
import com.example.autenticacioncontinua.monitoring.MotivoInvalidez
import com.example.autenticacioncontinua.monitoring.MuestraRecursos
import com.example.autenticacioncontinua.monitoring.ResumenRecursos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paso de resumen en memoria a fila de la base.
 *
 * Lo que se comprueba aquí es que la conversión no pierde la distinción entre
 * "no se pudo medir" y "midió cero", que es el fallo que invalidó las 676
 * mediciones anteriores. Un mapeo que ponga 0 donde había null volvería a
 * introducirlo por la puerta de atrás.
 */
class MedicionEntityTest {

    private fun muestra(t: Long, carga: Long? = 3_000_000L, cargando: Boolean = false) =
        MuestraRecursos(t, carga, -250_000L, 150_000L, cargando)

    @Test
    fun `un bloque medible viaja entero a la fila`() {
        val resumen = ResumenRecursos.desde(
            "ronda_fl", listOf(
                muestra(0, 3_000_000), muestra(1_000, 2_999_000), muestra(2_000, 2_998_000)
            )
        )
        val fila = MedicionRecursosEntity.desde(resumen, "ronda_fl", "acc_gyro", "federado", 12345L)

        assertEquals("ronda_fl", fila.etiqueta)
        assertEquals("acc_gyro", fila.configSensores)
        assertEquals("federado", fila.regimenAprendizaje)
        assertEquals(2_000L, fila.duracionMs)
        assertEquals(3, fila.nMuestras)
        assertEquals(2_000L, fila.consumoMicroAh)
        assertEquals(12345L, fila.tMs)
        assertEquals("", fila.invalidez)
        assertTrue(fila.esValida)
    }

    @Test
    fun `sin contador de carga la fila guarda null y no cero`() {
        val resumen = ResumenRecursos.desde(
            "reposo", listOf(muestra(0, null), muestra(500, null), muestra(1_000, null))
        )
        val fila = MedicionRecursosEntity.desde(resumen, "reposo", "acc", "local")

        assertNull(fila.consumoMicroAh)
        assertNull(fila.consumoMicroAhPorHora)
        // Pero la fila SI vale: la cifra sale de integrar la corriente, y el
        // metodo viaja con ella para que nadie la promedie con las del contador.
        assertEquals(MetodoConsumo.INTEGRACION_DE_CORRIENTE.name, fila.metodoConsumo)
        assertTrue(fila.esValida)
        assertNotNull(fila.tasaConsumoMicroAhPorHora)
    }

    @Test
    fun `una fila sin ninguna medida de energia queda marcada invalida`() {
        val resumen = ResumenRecursos.desde(
            "reposo", listOf(
                MuestraRecursos(0, null, null, 150_000, false),
                MuestraRecursos(500, null, null, 150_000, false),
                MuestraRecursos(1_000, null, null, 150_000, false)
            )
        )
        val fila = MedicionRecursosEntity.desde(resumen, "reposo", "acc", "local")

        assertEquals(MetodoConsumo.NINGUNO.name, fila.metodoConsumo)
        assertNull(fila.tasaConsumoMicroAhPorHora)
        assertFalse(fila.esValida)
    }

    @Test
    fun `los motivos de descarte se guardan por nombre y separados por coma`() {
        // Cargando Y con el contador quieto: dos motivos a la vez.
        val resumen = ResumenRecursos.desde(
            "b", listOf(
                muestra(0, 3_000_000, cargando = true),
                muestra(500, 3_000_000),
                muestra(1_000, 3_000_000)
            )
        )
        val fila = MedicionRecursosEntity.desde(resumen, "inferencia", "acc", "local")

        val motivos = fila.invalidez.split(",").toSet()
        assertTrue(MotivoInvalidez.CARGANDO.name in motivos)
        assertTrue(MotivoInvalidez.CONTADOR_SIN_VARIACION.name in motivos)
        // La consulta del DAO filtra por invalidez = '': una fila con motivos
        // no debe colarse en los promedios.
        assertFalse(fila.invalidez.isEmpty())
    }

    @Test
    fun `una serie de latencias viaja entera a la fila`() {
        val e = EstadisticaLatencia.desde("inferencia_ventana", listOf(10.0, 11.0, 10.5, 9.5, 500.0))
        val fila = MedicionLatenciaEntity.desde(e, "acc_gyro", "federado", 999L)

        assertEquals("inferencia_ventana", fila.etiqueta)
        assertEquals(5, fila.n)
        assertEquals(10.5, fila.medianaMs, 0.001)
        assertEquals(500.0, fila.maxMs, 0.001)
        assertEquals(999L, fila.tMs)
    }
}
