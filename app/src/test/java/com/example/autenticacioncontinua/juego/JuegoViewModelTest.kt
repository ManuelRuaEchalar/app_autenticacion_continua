package com.example.autenticacioncontinua.juego

import com.example.autenticacioncontinua.controlada.AmbientalEnMemoria
import com.example.autenticacioncontinua.controlada.ExportadorFalso
import com.example.autenticacioncontinua.presentation.controlada.EstadoExportacion
import com.example.autenticacioncontinua.controlada.FuenteFalsa
import com.example.autenticacioncontinua.controlada.SesionesEnMemoria
import com.example.autenticacioncontinua.controlada.TramosEnMemoria
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EstadoSesion
import com.example.autenticacioncontinua.data.sensor.CapturaInercial
import com.example.autenticacioncontinua.domain.sensor.ConfiguracionSensores
import com.example.autenticacioncontinua.domain.juego.FaseDeSesion
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import com.example.autenticacioncontinua.domain.tecleo.FaseDePulsacion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.domain.textos.Parrafo
import com.example.autenticacioncontinua.domain.textos.SelectorDeParrafos
import com.example.autenticacioncontinua.presentation.controlada.JuegoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La visita completa, simulada en milisegundos.
 *
 * Es la prueba que cubre lo que el plan pide de la fase 7 y que ninguna otra
 * puede dar: que la ACLIMATACIÓN NO DEJE RASTRO en la base, que cada bloque
 * quede etiquetado con su idioma, y que una interrupción marque el bloque en vez
 * de falsear su duración.
 *
 * El reloj del ViewModel se engancha al reloj VIRTUAL de `runTest`, así que una
 * visita de cinco minutos con tres bloques cronometrados corre en unos
 * milisegundos y sin un solo `Thread.sleep`. Es el mismo montaje que
 * `ProtocoloDeBloquesTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JuegoViewModelTest {

    private val despachador = StandardTestDispatcher()
    private lateinit var sesiones: SesionesEnMemoria
    private lateinit var ambiental: AmbientalEnMemoria
    private lateinit var tramos: TramosEnMemoria
    private lateinit var exportador: ExportadorFalso
    private lateinit var acelerometro: FuenteFalsa
    private lateinit var giroscopio: FuenteFalsa
    private lateinit var magnetometro: FuenteFalsa

    /** Las capturas creadas, una por bloque, para poder mirarlas después. */
    private val capturas = mutableListOf<CapturaInercial>()

    private val selector = SelectorDeParrafos(
        mapOf(
            "es" to (1..40).map { Parrafo("es_%04d".format(it), "es", TEXTO) },
            "la" to (1..40).map { Parrafo("la_%04d".format(it), "la", TEXTO) }
        )
    )

    @Before
    fun preparar() {
        Dispatchers.setMain(despachador)
        sesiones = SesionesEnMemoria()
        sesiones.seudonimoDe[PARTICIPANTE] = "P01"
        ambiental = AmbientalEnMemoria()
        tramos = TramosEnMemoria()
        exportador = ExportadorFalso()
        acelerometro = FuenteFalsa(TipoSensor.ACELEROMETRO)
        giroscopio = FuenteFalsa(TipoSensor.GIROSCOPIO)
        magnetometro = FuenteFalsa(TipoSensor.MAGNETOMETRO)
        capturas.clear()
    }

    @After
    fun limpiar() = Dispatchers.resetMain()

    /**
     * @param alcance el de la propia prueba, para que la captura corra sobre el
     *   reloj virtual. Con el `Dispatchers.IO` por defecto, las corrutinas del
     *   escritor viven fuera del scheduler y `advanceUntilIdle` no las espera.
     */
    private fun TestScope.vm() = JuegoViewModel(
        sesiones = sesiones,
        selector = selector,
        capturaDe = {
            CapturaInercial(
                acelerometro = acelerometro,
                giroscopio = giroscopio,
                magnetometro = magnetometro,
                repositorio = sesiones,
                // Los tres sensores: es lo que comprueba `los sensores se paran
                // al acabar la visita`, y con la configuración de por defecto el
                // magnetómetro ni siquiera se registraría.
                configuracion = { ConfiguracionSensores.D },
                alcance = this
            ).also { capturas += it }
        },
        ambiental = ambiental,
        tramos = tramos,
        exportador = exportador,
        // El reloj del dominio y el de las corrutinas son el MISMO. Si fueran
        // distintos, el bloque terminaria cuando lo dijera uno y las duraciones
        // se registrarian segun el otro.
        ahora = { despachador.scheduler.currentTime },
        bateria = { 80f },
        semillaDe = { SEMILLA }
    )

    /** Una muestra de acelerometro con su giroscopio, que es lo que el alineador exige. */
    private fun emitirMuestra(tNs: Long) {
        giroscopio.emitir(tNs)
        acelerometro.emitir(tNs)
    }

    /**
     * Espera a que la captura se haya suscrito a los sensores.
     *
     * Sin esto la prueba emitiria contra un `SharedFlow` sin colectores, que
     * descarta lo emitido en silencio: mediria su propia carrera, no el codigo.
     */
    private fun TestScope.esperarColector() {
        repeat(20) {
            if (acelerometro.hayColector && giroscopio.hayColector) return
            runCurrent()
        }
        error("la captura no se suscribio a los sensores")
    }

    /**
     * Avanza el reloj virtual.
     *
     * El `runCurrent` de después no es adorno: `advanceTimeBy` de
     * kotlinx-coroutines-test 1.8 ejecuta lo programado ANTES del instante
     * destino, no lo que cae justo en él, y el tic del ViewModel cae justo ahí.
     */
    private fun TestScope.avanzar(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun JuegoViewModel.teclear(c: String, t: Long) {
        onPulsacion(
            PulsacionCruda(
                FaseDePulsacion.ABAJO, c, tMs = t,
                x = 5f, y = 6f, presion = 0.4f, area = 11f
            )
        )
        onPulsacion(PulsacionCruda(FaseDePulsacion.ARRIBA, c, tMs = t + 80))
    }

    // ------------------------------------------------------------------
    // La aclimatación no deja rastro
    // ------------------------------------------------------------------

    /**
     * ES LA RAZÓN DE SER DE LA FASE. Las primeras pulsaciones con un teclado
     * desconocido miden la familiaridad con la interfaz, no a la persona; si
     * entraran en el corpus, el modelo aprendería «novato con este teléfono».
     */
    @Test
    fun `durante la aclimatacion no se abre ningun bloque ni se guarda nada`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        // `runCurrent` y no `avanzar`: la visita tiene que arrancar en el
        // instante cero del reloj virtual. Un solo milisegundo de adelanto
        // descuadraria todas las cuentas de duracion que siguen.
        runCurrent()

        assertEquals(FaseDeSesion.Aclimatacion, v.estado.value.fase)

        v.teclear("h", 100)
        v.teclear("o", 300)
        avanzar(FaseDeSesion.ACLIMATACION_MS / 2)

        assertTrue("ningun bloque abierto todavia", sesiones.bloques.isEmpty())
        assertTrue("ningun evento guardado", sesiones.eventos.isEmpty())
    }

    @Test
    fun `la aclimatacion dura lo que dice y da paso al primer bloque`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        // `runCurrent` y no `avanzar`: la visita tiene que arrancar en el
        // instante cero del reloj virtual. Un solo milisegundo de adelanto
        // descuadraria todas las cuentas de duracion que siguen.
        runCurrent()

        avanzar(FaseDeSesion.ACLIMATACION_MS - 1)
        assertEquals(FaseDeSesion.Aclimatacion, v.estado.value.fase)

        avanzar(JuegoViewModel.TIC_MS)
        val fase = v.estado.value.fase
        assertTrue("ahora toca el bloque 0", fase is FaseDeSesion.Bloque)
        assertEquals(0, (fase as FaseDeSesion.Bloque).indice)
        assertEquals(1, sesiones.bloques.size)
    }

    // ------------------------------------------------------------------
    // Visita completa
    // ------------------------------------------------------------------

    @Test
    fun `una visita entera deja tres bloques y la sesion COMPLETA`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertEquals(3, sesiones.bloques.size)
        assertEquals(listOf(0, 1, 2), sesiones.bloques.map { it.indice })
        assertTrue("todos cerrados", sesiones.bloques.all { it.finMs > 0 })
        assertFalse("ninguno interrumpido", sesiones.bloques.any { it.interrumpido })

        assertEquals(EstadoSesion.COMPLETA.name, sesiones.sesiones.single().estado)
        assertEquals(EstadoSesion.COMPLETA, v.estado.value.estadoFinal)
        assertEquals(FaseDeSesion.Fin, v.estado.value.fase)
        assertEquals(3, v.estado.value.resumen.size)
    }

    /**
     * Dos bloques en español y uno en latín, con el latín en la posición que le
     * toca a la visita. En la visita 1 va el primero.
     */
    @Test
    fun `cada bloque queda etiquetado con el idioma que le tocaba`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        val idiomas = sesiones.bloques.sortedBy { it.indice }.map { it.idioma }
        assertEquals(selector.idiomasDeSesion(1), idiomas)
        assertEquals(1, idiomas.count { it == BloqueEntity.IDIOMA_LATIN })
    }

    @Test
    fun `lo tecleado dentro de un bloque se guarda con ese bloque`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        avanzar(FaseDeSesion.ACLIMATACION_MS + JuegoViewModel.TIC_MS)

        val bloque = sesiones.bloques.single()
        "hola".forEachIndexed { i, c -> v.teclear(c.toString(), 1_000L + i * 100) }
        advanceUntilIdle()

        val suyos = sesiones.eventosDe(bloque.id)
        assertEquals(4, suyos.size)
        assertEquals(listOf("h", "o", "l", "a"), suyos.map { it.recibido })

        val cerrado = sesiones.bloques.first { it.id == bloque.id }
        assertEquals(4, cerrado.pulsaciones)
        assertTrue("se anotan los parrafos mostrados", cerrado.parrafosUsados.isNotEmpty())
    }

    /**
     * Entre que el bloque se corta y el bucle se entera pasa como mucho un tic.
     * Una tecla que caiga en ese hueco pertenecería a un bloque ya cerrado, y
     * entraría en la base con un tiempo posterior a `finMs`.
     */
    @Test
    fun `las pulsaciones posteriores al corte del bloque se ignoran`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        runCurrent()
        avanzar(FaseDeSesion.ACLIMATACION_MS + JuegoViewModel.TIC_MS)

        v.teclear("h", 1_000)
        v.onPausa("llamada entrante")
        v.teclear("z", 2_000)          // ya no cuenta: el bloque esta cortado
        advanceUntilIdle()

        assertEquals(1, sesiones.eventos.count { it.recibido == "h" })
        assertEquals(0, sesiones.eventos.count { it.recibido == "z" })
        assertEquals(1, sesiones.bloques.single().pulsaciones)
    }

    // ------------------------------------------------------------------
    // Interrupción
    // ------------------------------------------------------------------

    /**
     * El tiempo NO se falsea para que cuadre: se marca. Y la visita se aborta,
     * porque si la aplicación se fue al fondo nadie está tecleando y seguir al
     * bloque siguiente fabricaría un bloque vacío que parecería un participante
     * que se rindió.
     */
    @Test
    fun `una interrupcion marca el bloque en curso y aborta la visita`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        avanzar(FaseDeSesion.ACLIMATACION_MS + JuegoViewModel.TIC_MS)
        avanzar(BloqueEntity.DURACION_MS / 2)

        v.onPausa("llamada entrante")
        advanceUntilIdle()

        val bloque = sesiones.bloques.single()
        assertTrue(bloque.interrumpido)
        assertEquals("llamada entrante", bloque.motivoInterrupcion)
        assertEquals("no se abrieron los siguientes", 1, sesiones.bloques.size)
        assertEquals(EstadoSesion.ABORTADA.name, sesiones.sesiones.single().estado)
    }

    /**
     * Los bloques que sí se completaron se conservan y son utilizables: es
     * exactamente la semántica de ABORTADA frente a INVALIDADA.
     */
    @Test
    fun `al abortar se conservan los bloques ya completos`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        // Aclimatación entera y el primer bloque entero.
        avanzar(FaseDeSesion.ACLIMATACION_MS + BloqueEntity.DURACION_MS + JuegoViewModel.TIC_MS * 2)
        avanzar(BloqueEntity.DURACION_MS / 4)

        v.onPausa("pantalla apagada")
        advanceUntilIdle()

        assertEquals(2, sesiones.bloques.size)
        assertFalse("el primero llego al final", sesiones.bloques[0].interrumpido)
        assertTrue("el segundo se corto", sesiones.bloques[1].interrumpido)
        assertEquals(EstadoSesion.ABORTADA.name, sesiones.sesiones.single().estado)
    }

    /**
     * `onStop` se dispara también al salir de la pantalla de resumen, cuando la
     * sesión ya está cerrada en la base. No debe tocar nada.
     */
    @Test
    fun `una pausa despues de terminar no cambia nada`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()
        val antes = sesiones.sesiones.single()

        v.onPausa("la aplicacion paso a segundo plano")
        advanceUntilIdle()

        assertEquals(antes, sesiones.sesiones.single())
        assertEquals(EstadoSesion.COMPLETA.name, sesiones.sesiones.single().estado)
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * La pantalla se recompone varias veces por segundo; una segunda llamada
     * abriría una segunda fila para la misma visita.
     */
    @Test
    fun `iniciar dos veces seguidas no abre dos sesiones`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        // `runCurrent` y no `avanzar`: la visita tiene que arrancar en el
        // instante cero del reloj virtual. Un solo milisegundo de adelanto
        // descuadraria todas las cuentas de duracion que siguen.
        runCurrent()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertEquals(1, sesiones.sesiones.size)
        assertEquals(3, sesiones.bloques.size)
    }

    /**
     * En una tarde de campo se miden varios participantes seguidos sin cerrar la
     * aplicación, y el ViewModel vive lo que viva la Activity.
     */
    @Test
    fun `terminada una visita se puede empezar la siguiente`() = runTest {
        sesiones.seudonimoDe[2L] = "P02"
        val v = vm()

        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()
        v.iniciar(2L, "A")
        advanceUntilIdle()

        assertEquals(2, sesiones.sesiones.size)
        assertEquals(6, sesiones.bloques.size)
        assertEquals("P02", v.estado.value.seudonimo)
        assertEquals(3, v.estado.value.resumen.size)
    }

    @Test
    fun `un participante que no existe no abre sesion y se explica`() = runTest {
        val v = vm()
        v.iniciar(999L, "A")
        advanceUntilIdle()

        assertTrue(sesiones.sesiones.isEmpty())
        assertNotNull(v.estado.value.error)
    }

    // ------------------------------------------------------------------
    // Selección de párrafos
    // ------------------------------------------------------------------

    /**
     * Regla 2.6 del plan: un participante no repite párrafo entre sus propias
     * sesiones. Un texto ya conocido se teclea distinto que uno nuevo, así que
     * la familiaridad con el material quedaría confundida con el número de
     * sesión.
     */
    @Test
    fun `la segunda visita no repite parrafos de la primera`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()
        val primera = parrafosDeLasSesiones()

        v.iniciar(PARTICIPANTE, "B")
        advanceUntilIdle()
        val todos = parrafosDeLasSesiones()

        val segunda = todos - primera
        assertTrue("la segunda visita mostro parrafos", segunda.isNotEmpty())
        assertTrue(
            "y ninguno estaba en la primera",
            segunda.intersect(primera).isEmpty()
        )
    }

    /** Dentro de una misma visita, los tres bloques tampoco se pisan. */
    @Test
    fun `los bloques de una misma visita no comparten parrafo`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        val porBloque = sesiones.bloques.map { it.parrafosUsados.split(',').toSet() }
        for (i in porBloque.indices) {
            for (j in i + 1 until porBloque.size) {
                assertTrue(
                    "los bloques $i y $j comparten parrafo",
                    porBloque[i].intersect(porBloque[j]).isEmpty()
                )
            }
        }
    }

    private fun parrafosDeLasSesiones(): Set<String> =
        sesiones.bloques.flatMap { it.parrafosUsados.split(',') }
            .filter { it.isNotBlank() }
            .toSet()

    // ------------------------------------------------------------------
    // Fase 8: la recoleccion ambiental y la captura inercial
    // ------------------------------------------------------------------

    /**
     * LA RESTRICCIÓN R1 EN FORMA DE PRUEBA.
     *
     * Durante una visita el teléfono lo usa alguien que no es el dueño. Si la
     * recolección ambiental siguiera corriendo, sus muestras caerían en
     * `accelerometer_data` como uso del dueño y el modelo personal se
     * entrenaría con datos de otra persona. El fallo no da ningún síntoma.
     *
     * Y el ORDEN importa: suspender DESPUÉS de abrir la sesión dejaría una
     * ventana en la que todavía se puede arrancar una ráfaga.
     */
    @Test
    fun `la recoleccion ambiental se suspende antes de abrir la sesion`() = runTest {
        val v = vm()
        assertFalse(ambiental.estaSuspendido)

        v.iniciar(PARTICIPANTE, "A")
        runCurrent()

        assertTrue("suspendida ya en la aclimatacion", ambiental.estaSuspendido)
        assertEquals(1, ambiental.suspensiones)
        assertEquals(1, sesiones.sesiones.size)
    }

    @Test
    fun `al terminar la visita la recoleccion ambiental se reanuda`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertFalse("no puede quedarse suspendida", ambiental.estaSuspendido)
        assertEquals(1, ambiental.reanudaciones)
    }

    /**
     * Dejarla suspendida seria PEOR que no haberla suspendido: el dueño dejaría
     * de recoger indefinidamente y sin ningún aviso.
     */
    @Test
    fun `tambien se reanuda cuando la visita se aborta`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        avanzar(FaseDeSesion.ACLIMATACION_MS + JuegoViewModel.TIC_MS)
        v.onPausa("llamada entrante")
        advanceUntilIdle()

        assertFalse(ambiental.estaSuspendido)
        assertEquals(EstadoSesion.ABORTADA.name, sesiones.sesiones.single().estado)
    }

    /**
     * Los TIRANTES del cinturón anterior: si la suspensión fallara y entraran
     * muestras ambientales durante la visita, el tramo marcado como NO del dueño
     * hace que `ExclusionEtiquetada` las descarte igualmente.
     */
    @Test
    fun `la visita queda registrada como tramo que no es del dueño`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        runCurrent()

        val tramo = tramos.tramos.single()
        assertEquals("P01", tramo.participantId)
        assertFalse("marcado como del dueño no excluiria nada", tramo.isOwner)
        assertTrue("abierto mientras dura la visita", tramo.enCurso)

        advanceUntilIdle()
        assertFalse("y cerrado al terminar", tramos.tramos.single().enCurso)
    }

    // ------------------------------------------------------------------
    // Fase 9: la visita no se cierra sin copia (R5)
    // ------------------------------------------------------------------

    /**
     * Al terminar, el paquete se escribe SOLO. Nadie tiene que pulsar nada.
     *
     * Es la forma de que un requisito obligatorio se cumpla siempre: si
     * dependiera de un boton, se incumpliria el dia que hay prisa, y ese dia no
     * se distingue de los demas hasta que meses despues falta una visita.
     */
    @Test
    fun `al terminar la visita se exporta sin que nadie lo pida`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertEquals("una exportacion, de esta sesion", 1, exportador.exportadas.size)
        assertTrue(v.estado.value.exportacion is EstadoExportacion.Hecha)
    }

    /** Y hasta que no esta escrita y releida, no se sale. */
    @Test
    fun `no se puede salir de la visita antes de que la copia este hecha`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()
        assertTrue("con la copia hecha si se sale", v.estado.value.puedeSalir)
    }

    /**
     * SI LA EXPORTACION FALLA, LA SESION YA ESTA CERRADA Y LOS DATOS ESTAN.
     *
     * Es el orden que importa: exportar va DESPUES de cerrar. Al reves, un
     * fallo de disco dejaria la sesion abierta y la siguiente visita del mismo
     * participante la heredaria, mezclando dos dias en un episodio.
     */
    @Test
    fun `si la exportacion falla la sesion queda cerrada y los datos intactos`() = runTest {
        exportador.falla = true
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        val sesion = sesiones.sesiones.single()
        assertFalse("la sesion tiene que quedar cerrada igualmente", sesion.estaAbierta)
        assertEquals(EstadoSesion.COMPLETA.name, sesion.estado)
        assertEquals("y sus tres bloques siguen ahi", 3, sesiones.bloques.size)

        assertTrue(v.estado.value.exportacion is EstadoExportacion.Fallida)
        assertFalse("pero NO se deja salir sin copia", v.estado.value.puedeSalir)
    }

    /** Y se puede reintentar sin repetir la visita. */
    @Test
    fun `la exportacion fallida se puede reintentar`() = runTest {
        exportador.falla = true
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()
        assertFalse(v.estado.value.puedeSalir)

        exportador.falla = false
        v.exportar()
        advanceUntilIdle()

        assertTrue("ya hay copia", v.estado.value.exportacion is EstadoExportacion.Hecha)
        assertTrue(v.estado.value.puedeSalir)
    }

    /**
     * UN PAQUETE QUE SE ESCRIBE PERO NO SE RELEE BIEN NO CUENTA COMO COPIA.
     *
     * Es la diferencia entre "se escribio un fichero" y "hay una copia". Un zip
     * truncado tiene el nombre correcto y un tamano plausible en el listado; lo
     * unico que lo delata es volver a abrirlo y comprobar las huellas.
     */
    @Test
    fun `un paquete corrupto no se da por bueno`() = runTest {
        exportador.corrupto = true
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertTrue(v.estado.value.exportacion is EstadoExportacion.Fallida)
        assertFalse("no se sale con una copia que no se relee", v.estado.value.puedeSalir)
    }

    /** Cada bloque enciende y apaga los sensores; ninguno se queda vivo. */
    @Test
    fun `los sensores se paran al acabar la visita`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        advanceUntilIdle()

        assertEquals("una captura por bloque", 3, capturas.size)
        assertTrue("ninguna sigue capturando", capturas.none { it.estaCapturando })
        assertTrue(acelerometro.detenida && giroscopio.detenida && magnetometro.detenida)
    }

    /**
     * LO QUE FALTABA DE LA FASE 7: `muestras_inerciales` estaba vacía.
     *
     * No basta con comprobar que se llamó a `iniciar`: lo que importa es que las
     * muestras acaban en la base atadas al bloque correcto, y entre una cosa y
     * otra están el alineador —que descarta el acelerómetro sin giroscopio— y el
     * escritor por lotes.
     */
    @Test
    fun `las muestras inerciales del bloque acaban en la base con su bloqueId`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        avanzar(FaseDeSesion.ACLIMATACION_MS + JuegoViewModel.TIC_MS)

        val bloque = sesiones.bloques.single()
        esperarColector()
        repeat(MUESTRAS) { i -> emitirMuestra(tNs = i * 10_000_000L) }
        advanceUntilIdle()

        val suyas = sesiones.muestras.filter { it.bloqueId == bloque.id }
        assertEquals(MUESTRAS, suyas.size)
        assertEquals("no hay muestras de ningun otro bloque", MUESTRAS, sesiones.muestras.size)
        assertEquals(
            "y llegan en orden, con su reloj monotono",
            (0 until MUESTRAS).map { it * 10_000_000L },
            suyas.map { it.tMonotonoNs }
        )
    }

    /** Durante la aclimatación no se capturan sensores: no hay bloque al que atarlos. */
    @Test
    fun `la aclimatacion no captura muestras inerciales`() = runTest {
        val v = vm()
        v.iniciar(PARTICIPANTE, "A")
        runCurrent()

        repeat(10) { i -> emitirMuestra(tNs = i * 10_000_000L) }
        avanzar(FaseDeSesion.ACLIMATACION_MS / 2)

        assertTrue(capturas.isEmpty())
        assertTrue(sesiones.muestras.isEmpty())
    }

    private companion object {
        const val PARTICIPANTE = 1L
        const val MUESTRAS = 25
        const val SEMILLA = 12345L

        /** Corto a propósito: se agota rápido y obliga a encadenar párrafos. */
        const val TEXTO = "hola que tal"
    }
}
