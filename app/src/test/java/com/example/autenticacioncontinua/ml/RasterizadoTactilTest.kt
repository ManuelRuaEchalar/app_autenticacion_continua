package com.example.autenticacioncontinua.ml

import com.example.autenticacioncontinua.domain.ml.RasterizadoTactil
import com.example.autenticacioncontinua.domain.ml.RasterizadoTactil.Pulsacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del canal táctil de la configuración C.
 *
 * Lo que se cubre aquí no es "un array se rellena" sino las cuatro propiedades
 * de las que depende que C signifique algo. Las tres primeras tienen que
 * coincidir con lo que hace `colab_preentreno_configuraciones.ipynb` sobre
 * HMOG: si divergen, el modelo se pre-entrena con una señal y recibe otra en el
 * teléfono, y eso no falla — sólo empeora las puntuaciones en silencio.
 */
class RasterizadoTactilTest {

    /** Rejilla de 100 muestras a 50 Hz, en nanosegundos: 20 ms de paso. */
    private fun rejilla(n: Int = 100, pasoMs: Long = 20): LongArray =
        LongArray(n) { it * pasoMs * 1_000_000L }

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `el contacto marca solo el tramo entre bajada y subida`() {
        val r = rejilla()
        val out = RasterizadoTactil.rasterizar(
            r, listOf(Pulsacion(tDown = ms(200), tUp = ms(260), x = 1f, y = 2f))
        )
        val contacto = out[RasterizadoTactil.CONTACTO]

        // 200..260 ms a 20 ms de paso son los índices 10..13.
        for (i in 0 until 10) assertEquals("antes de bajar", 0f, contacto[i], 0f)
        for (i in 10..13) assertEquals("durante el contacto", 1f, contacto[i], 0f)
        for (i in 14 until r.size) assertEquals("tras subir", 0f, contacto[i], 0f)
    }

    @Test
    fun `una pulsacion sin cerrar se ignora en vez de durar hasta el final`() {
        // `EventoTecleoEntity` guarda tUpMs = 0 cuando la tecla seguía pulsada
        // al terminar el bloque. Tratarla como un contacto abierto marcaría el
        // resto del bloque entero y falsearía el ciclo de trabajo, que es
        // justamente lo que el canal mide.
        val r = rejilla()
        val out = RasterizadoTactil.rasterizar(
            r, listOf(Pulsacion(tDown = ms(200), tUp = 0, x = 1f, y = 2f))
        )
        assertEquals(0f, out[RasterizadoTactil.CONTACTO].max(), 0f)
    }

    @Test
    fun `la posicion se mantiene entre pulsaciones, no se interpola`() {
        // Es la diferencia entre "el dedo estuvo aquí y luego allí" y "el dedo
        // viajó en línea recta de aquí a allí", que es un trazo que no ocurrió.
        val r = rejilla()
        val out = RasterizadoTactil.rasterizar(
            r, listOf(
                Pulsacion(ms(200), ms(220), x = 100f, y = 500f),
                Pulsacion(ms(600), ms(620), x = 300f, y = 700f)
            )
        )
        val x = out[RasterizadoTactil.X]

        // Entre la primera y la segunda pulsación (índices 11..29) X tiene que
        // valer exactamente 100, no algo entre 100 y 300.
        for (i in 11..29) assertEquals("índice $i", 100f, x[i], 0f)
        assertEquals(300f, x[35], 0f)
    }

    @Test
    fun `antes de la primera pulsacion la posicion es la de esa pulsacion`() {
        // Arrancar en cero metería un escalón de 100 px al principio de cada
        // bloque que el convolucional leería como un gesto.
        val r = rejilla()
        val out = RasterizadoTactil.rasterizar(
            r, listOf(Pulsacion(ms(400), ms(420), x = 250f, y = 800f))
        )
        assertEquals(250f, out[RasterizadoTactil.X][0], 0f)
        assertEquals(800f, out[RasterizadoTactil.Y][0], 0f)
    }

    @Test
    fun `un bloque sin pulsaciones da ceros y no inventa posicion`() {
        // Un bloque sin tecleo existe: el participante pudo quedarse parado. El
        // modelo tiene que poder distinguirlo de uno con actividad, así que no
        // se rellena con la posición del centro de la pantalla ni nada similar.
        val out = RasterizadoTactil.rasterizar(rejilla(), emptyList())
        assertEquals(RasterizadoTactil.CANALES, out.size)
        for (canal in out) assertTrue(canal.all { it == 0f })
    }

    @Test
    fun `pulsaciones solapadas no se cuentan dos veces`() {
        // Dos dedos, o un repetido de tecla, pueden solaparse. El canal es
        // "hay contacto", no "cuántos contactos", así que la unión de los dos
        // tramos tiene que dar unos, no doses.
        val r = rejilla()
        val out = RasterizadoTactil.rasterizar(
            r, listOf(
                Pulsacion(ms(200), ms(300), x = 1f, y = 1f),
                Pulsacion(ms(250), ms(350), x = 2f, y = 2f)
            )
        )
        val contacto = out[RasterizadoTactil.CONTACTO]
        assertEquals(1f, contacto.max(), 0f)
        for (i in 10..17) assertEquals("índice $i", 1f, contacto[i], 0f)
    }

    @Test
    fun `el ciclo de trabajo de un tecleo realista cae donde debe`() {
        // Permanencia ~74 ms y vuelo ~2234 ms es lo medido en el corpus de
        // pre-entrenamiento. El ciclo de trabajo resultante ronda el 3%, y es el
        // orden de magnitud que el encoder de C va a ver. Si esta prueba
        // empezara a dar 0.7, sería el mismo fallo de reloj que ya apareció al
        // preparar HMOG.
        val n = 5_000                       // 100 s a 50 Hz
        val r = rejilla(n)
        val pulsaciones = ArrayList<Pulsacion>()
        var t = 0L
        while (t < 100_000) {
            pulsaciones.add(Pulsacion(ms(t), ms(t + 74), x = 100f, y = 200f))
            t += 74 + 2234
        }
        val contacto = RasterizadoTactil.rasterizar(r, pulsaciones)[RasterizadoTactil.CONTACTO]
        val ciclo = contacto.count { it == 1f }.toDouble() / n

        assertTrue("ciclo de trabajo fuera de rango: $ciclo", ciclo in 0.02..0.10)
    }

    @Test
    fun `las pulsaciones desordenadas no cruzan tramos`() {
        // Los eventos de HMOG no venían ordenados por su reloj de evento, y sin
        // ordenar el emparejado cruzaba pulsaciones distintas. Aquí llegan de
        // una consulta con ORDER BY, pero la clase no debe depender de eso.
        val r = rejilla()
        val ordenadas = listOf(
            Pulsacion(ms(200), ms(240), 1f, 1f),
            Pulsacion(ms(600), ms(640), 2f, 2f)
        )
        val desordenadas = ordenadas.reversed()

        val a = RasterizadoTactil.rasterizar(r, ordenadas)[RasterizadoTactil.CONTACTO]
        val b = RasterizadoTactil.rasterizar(r, desordenadas)[RasterizadoTactil.CONTACTO]
        assertTrue(a.contentEquals(b))
    }
}
