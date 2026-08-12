package com.example.autenticacioncontinua.federated

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Criterio de aprobación "test de empaquetado" de la Fase 2: lo que Kotlin
 * serializa tiene que ser exactamente lo que el servidor de Python espera
 * leer con `np.load`.
 */
class NpyHelperTest {

    @Test
    fun `el round-trip conserva los valores bit a bit`() {
        val original = FloatArray(25_464) { (it % 977) * 0.001f - 0.5f }
        val restored = NpyHelper.fromNpy(NpyHelper.toNpy(original))
        assertArrayEquals(original, restored, 0f)
    }

    @Test
    fun `la cabecera es un npy v1 valido y alineado`() {
        val buffer = NpyHelper.toNpy(FloatArray(10))
        val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

        assertEquals(0x93.toByte(), bytes[0])
        assertEquals("NUMPY", String(bytes, 1, 5, Charsets.US_ASCII))
        assertEquals(1, bytes[6].toInt())  // versión mayor
        assertEquals(0, bytes[7].toInt())  // versión menor

        val headerLength = ByteBuffer.wrap(bytes, 8, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        // numpy exige que el payload empiece en un múltiplo de 64 bytes.
        assertEquals(0, (10 + headerLength) % 64)

        val header = String(bytes, 10, headerLength, Charsets.US_ASCII)
        assertTrue("descr incorrecto: $header", header.contains("'descr': '<f4'"))
        assertTrue("shape incorrecto: $header", header.contains("'shape': (10,)"))
        assertTrue("debe terminar en salto de línea", header.endsWith("\n"))
    }

    @Test
    fun `el payload va en little-endian explicito`() {
        val buffer = NpyHelper.toNpy(floatArrayOf(1.0f))
        val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        val payloadStart = bytes.size - 4

        // 1.0f en IEEE-754 little-endian es 00 00 80 3F.
        assertEquals(0x00.toByte(), bytes[payloadStart])
        assertEquals(0x00.toByte(), bytes[payloadStart + 1])
        assertEquals(0x80.toByte(), bytes[payloadStart + 2])
        assertEquals(0x3F.toByte(), bytes[payloadStart + 3])
    }

    @Test
    fun `se tolera un buffer sin cabecera`() {
        val raw = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        raw.putFloat(1f).putFloat(2f).putFloat(3f)
        raw.rewind()

        assertArrayEquals(floatArrayOf(1f, 2f, 3f), NpyHelper.fromNpy(raw), 0f)
    }

    @Test
    fun `el vector vacio no rompe la serializacion`() {
        assertEquals(0, NpyHelper.fromNpy(NpyHelper.toNpy(FloatArray(0))).size)
    }
}
