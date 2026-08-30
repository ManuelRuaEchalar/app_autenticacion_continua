package com.example.autenticacioncontinua.controlada

import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.presentation.controlada.ParticipantesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de la gestión de participantes.
 *
 * Cubren las tres condiciones que pide la fase 6 —crear, buscar y seleccionar;
 * el historial correcto; y no poder empezar sin participante— y una cuarta que
 * salió al implementar: qué hacer cuando el investigador da de alta a alguien
 * que ya vino.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantesViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var participantes: ParticipantesEnMemoria
    private lateinit var sesiones: SesionesEnMemoria

    @Before
    fun preparar() {
        Dispatchers.setMain(despachador)
        participantes = ParticipantesEnMemoria()
        sesiones = SesionesEnMemoria()
    }

    @After
    fun limpiar() = Dispatchers.resetMain()

    private fun vm(dispositivo: String = "A") =
        ParticipantesViewModel(participantes, sesiones, dispositivo)

    // ------------------------------------------------------------------
    // Crear
    // ------------------------------------------------------------------

    @Test
    fun `dar de alta deja al nuevo participante creado y seleccionado`() = runTest {
        val v = vm()
        advanceUntilIdle()

        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        val e = v.estado.value
        assertEquals(1, e.participantes.size)
        assertEquals("P01", e.seleccionado?.seudonimo)
        assertNull(e.error)
        assertTrue("tras el alta se puede empezar", e.puedeIniciarSesion)
    }

    @Test
    fun `el seudonimo se normaliza al dar de alta`() = runTest {
        val v = vm()
        advanceUntilIdle()

        v.alta("  p07 ", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        assertEquals("P07", v.estado.value.seleccionado?.seudonimo)
    }

    @Test
    fun `un seudonimo invalido se rechaza con motivo y no crea nada`() = runTest {
        val v = vm()
        advanceUntilIdle()

        v.alta("participante con espacios", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        val e = v.estado.value
        assertNotNull("debe explicar por que", e.error)
        assertTrue(e.participantes.isEmpty())
        assertFalse(e.puedeIniciarSesion)
    }

    /**
     * Dos filas para la misma persona la partirían en dos identidades, y en un
     * análisis con partición disjunta por persona eso pone a la misma persona a
     * los dos lados. Pero tampoco es un error del programa: es que el
     * investigador vuelve a dar de alta a alguien que ya vino.
     */
    @Test
    fun `dar de alta a alguien que ya existe lo selecciona y avisa`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        v.alta("p01", "35-44", "m", "zurda", "alta")
        advanceUntilIdle()

        val e = v.estado.value
        assertEquals("no debe crear un segundo", 1, e.participantes.size)
        assertEquals("P01", e.seleccionado?.seudonimo)
        assertNotNull("debe avisar de que ya existia", e.aviso)
        assertNull("pero no es un error", e.error)
        // Y los datos del existente NO se pisan con los del alta repetida.
        assertEquals("25-34", e.seleccionado?.tramoEdad)
    }

    // ------------------------------------------------------------------
    // Buscar
    // ------------------------------------------------------------------

    @Test
    fun `el buscador filtra por seudonimo sin distinguir mayusculas`() = runTest {
        val v = vm()
        advanceUntilIdle()
        for (s in listOf("P01", "P02", "PILOTO")) {
            v.alta(s, "25-34", "f", "diestra", "ninguna")
            advanceUntilIdle()
        }

        v.filtrar("p0")
        assertEquals(listOf("P01", "P02"), v.estado.value.visibles.map { it.seudonimo })

        v.filtrar("piloto")
        assertEquals(listOf("PILOTO"), v.estado.value.visibles.map { it.seudonimo })

        v.filtrar("")
        assertEquals(3, v.estado.value.visibles.size)
    }

    @Test
    fun `filtrar no cambia quien esta seleccionado`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        v.filtrar("zzz")
        assertTrue(v.estado.value.visibles.isEmpty())
        assertEquals("P01", v.estado.value.seleccionado?.seudonimo)
    }

    // ------------------------------------------------------------------
    // Seleccionar y plan de visita
    // ------------------------------------------------------------------

    /**
     * Una sesión sin participante produciría bloques y muestras huérfanas,
     * imposibles de asignar a nadie después.
     */
    @Test
    fun `sin participante seleccionado no se puede iniciar sesion`() = runTest {
        val v = vm()
        advanceUntilIdle()
        assertFalse(v.estado.value.puedeIniciarSesion)

        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()
        assertTrue(v.estado.value.puedeIniciarSesion)

        v.deseleccionar()
        assertFalse(v.estado.value.puedeIniciarSesion)
        assertNull(v.estado.value.plan)
    }

    @Test
    fun `al seleccionar se calcula la visita y el dispositivo que toca`() = runTest {
        val v = vm(dispositivo = "A")
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")   // impar: empieza por A
        advanceUntilIdle()
        sesiones.seudonimoDe[1L] = "P01"

        v.seleccionar(v.estado.value.participantes.first())
        advanceUntilIdle()

        val plan = v.estado.value.plan
        assertNotNull(plan)
        assertEquals(1, plan!!.visita)
        assertEquals("A", plan.dispositivoEsperado)
        assertFalse("el terminal es el correcto", plan.dispositivoNoEsElEsperado)
    }

    /**
     * La app corre en el terminal en el que corre; no puede elegirlo. Lo que
     * hace es avisar, no bloquear: un participante que se presenta con el otro
     * móvil delante es mejor dato que ninguno.
     */
    @Test
    fun `avisa si el terminal no es el que tocaba, pero deja continuar`() = runTest {
        val v = vm(dispositivo = "B")
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()
        sesiones.seudonimoDe[1L] = "P01"

        v.seleccionar(v.estado.value.participantes.first())
        advanceUntilIdle()

        assertTrue(v.estado.value.plan!!.dispositivoNoEsElEsperado)
        assertTrue("avisar no es bloquear", v.estado.value.puedeIniciarSesion)
    }

    // ------------------------------------------------------------------
    // Historial y recuento
    // ------------------------------------------------------------------

    @Test
    fun `el historial devuelve las sesiones del participante y solo las suyas`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()
        v.alta("P02", "25-34", "m", "diestra", "ninguna")
        advanceUntilIdle()

        sesiones.abrir(1L, "A", 1L, 80f)
        sesiones.abrir(1L, "B", 2L, 80f)
        sesiones.abrir(2L, "A", 3L, 80f)

        assertEquals(2, v.historial(1L).size)
        assertEquals(1, v.historial(2L).size)
    }

    /**
     * El recuento cambia al terminar una sesión. Si el ViewModel se quedara con
     * la copia de la selección, seguiría diciendo el número viejo para siempre.
     */
    @Test
    fun `recargar refresca el recuento del participante seleccionado`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()
        assertEquals(0, v.estado.value.seleccionado?.sesionesHechas)

        participantes.sesionesPorParticipante[1L] = 3
        v.cargar()
        advanceUntilIdle()

        assertEquals(3, v.estado.value.seleccionado?.sesionesHechas)
        assertEquals("y sigue seleccionado", "P01", v.estado.value.seleccionado?.seudonimo)
    }

    @Test
    fun `una sesion invalidada no cuenta como sesion hecha`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("P01", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()

        val id = sesiones.abrir(1L, "A", 1L, 80f)
        sesiones.cerrar(id, EstadoSesion.COMPLETA, 78f)
        val mala = sesiones.abrir(1L, "A", 2L, 78f)
        sesiones.invalidar(mala, "participante equivocado")

        val utilizables = sesiones.sesionesDe(1L).count { it.esUtilizable }
        assertEquals(1, utilizables)
        assertEquals("pero la invalidada se conserva", 2, v.historial(1L).size)
    }

    @Test
    fun `limpiar mensajes borra el error y el aviso`() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.alta("x", "25-34", "f", "diestra", "ninguna")
        advanceUntilIdle()
        assertNotNull(v.estado.value.error)

        v.limpiarMensajes()
        assertNull(v.estado.value.error)
        assertNull(v.estado.value.aviso)
    }
}
