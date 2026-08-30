package com.example.autenticacioncontinua.domain.controlada

import com.example.autenticacioncontinua.domain.model.controlada.PlanDeDispositivos
import com.example.autenticacioncontinua.domain.model.controlada.PlanDeDispositivos.DISPOSITIVO_A
import com.example.autenticacioncontinua.domain.model.controlada.PlanDeDispositivos.DISPOSITIVO_B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pruebas del reparto de terminales entre visitas.
 *
 * Es una función de cuatro líneas, pero de ella depende que el efecto de la
 * persona y el del aparato se puedan separar en el análisis. En la recogida
 * ambiental no se pudieron: el 9.2% de la varianza era de dispositivo y estaba
 * confundida con la identidad, porque cada persona usaba siempre su propio
 * móvil. Un fallo aquí no rompe nada visible; simplemente devuelve el estudio a
 * esa situación 60 días después.
 */
class PlanDeDispositivosTest {

    @Test
    fun `alterna terminal en visitas consecutivas`() {
        val secuencia = (1..6).map { PlanDeDispositivos.dispositivoEsperado("P01", it) }
        assertEquals(
            listOf(DISPOSITIVO_A, DISPOSITIVO_B, DISPOSITIVO_A,
                DISPOSITIVO_B, DISPOSITIVO_A, DISPOSITIVO_B),
            secuencia
        )
    }

    /**
     * Si todos empezaran por A, el terminal A acumularía todas las primeras
     * visitas —las de participantes sin práctica— y el efecto del aparato
     * quedaría confundido con el del aprendizaje.
     */
    @Test
    fun `los impares empiezan por A y los pares por B`() {
        assertEquals(DISPOSITIVO_A, PlanDeDispositivos.dispositivoEsperado("P01", 1))
        assertEquals(DISPOSITIVO_A, PlanDeDispositivos.dispositivoEsperado("P07", 1))
        assertEquals(DISPOSITIVO_B, PlanDeDispositivos.dispositivoEsperado("P02", 1))
        assertEquals(DISPOSITIVO_B, PlanDeDispositivos.dispositivoEsperado("P24", 1))
    }

    @Test
    fun `tras diez visitas el reparto queda equilibrado para todo participante`() {
        for (n in 1..30) {
            val seudonimo = "P%02d".format(n)
            val reparto = PlanDeDispositivos.repartoIdeal(seudonimo, 10)
            assertEquals(
                "$seudonimo no reparte a partes iguales: $reparto",
                5, reparto[DISPOSITIVO_A]
            )
            assertEquals(5, reparto[DISPOSITIVO_B])
        }
    }

    /**
     * Con un número impar de visitas el reparto no puede ser exacto, pero la
     * diferencia no debe pasar de una: si un participante abandona a mitad, sus
     * datos siguen sirviendo para separar persona de dispositivo.
     */
    @Test
    fun `abandonar a mitad deja como mucho una sesion de diferencia`() {
        for (n in 1..30) {
            for (visitas in 1..10) {
                val reparto = PlanDeDispositivos.repartoIdeal("P%02d".format(n), visitas)
                val a = reparto[DISPOSITIVO_A] ?: 0
                val b = reparto[DISPOSITIVO_B] ?: 0
                assertTrue("P$n con $visitas visitas: $a vs $b", abs(a - b) <= 1)
            }
        }
    }

    /**
     * Es la razón de usar el seudónimo y no el identificador de la base: si un
     * alta se borra y se rehace, el identificador cambia y con él cambiaría todo
     * el reparto ya recogido.
     */
    @Test
    fun `el reparto depende del seudonimo y no de como se escriba alrededor`() {
        assertEquals(
            PlanDeDispositivos.dispositivoEsperado("P07", 3),
            PlanDeDispositivos.dispositivoEsperado("PARTICIPANTE-07", 3)
        )
    }

    @Test
    fun `un seudonimo sin digitos tambien reparte de forma estable`() {
        val primera = PlanDeDispositivos.dispositivoEsperado("PILOTO", 1)
        assertEquals(primera, PlanDeDispositivos.dispositivoEsperado("PILOTO", 1))
        assertTrue(primera == DISPOSITIVO_A || primera == DISPOSITIVO_B)
        // Y sigue alternando.
        val segunda = PlanDeDispositivos.dispositivoEsperado("PILOTO", 2)
        assertTrue(segunda != primera)
    }

    @Test
    fun `sobre 25 participantes las primeras visitas se reparten entre los dos`() {
        val primeras = (1..25).map { PlanDeDispositivos.dispositivoEsperado("P%02d".format(it), 1) }
        val enA = primeras.count { it == DISPOSITIVO_A }
        assertTrue("primeras visitas en A: $enA de 25", abs(enA - 25 / 2) <= 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `la visita cero es un error de programacion`() {
        PlanDeDispositivos.dispositivoEsperado("P01", 0)
    }
}
