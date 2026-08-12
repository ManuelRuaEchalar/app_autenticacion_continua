package com.example.autenticacioncontinua.federated

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialización `.npy` para el transporte de Flower.
 *
 * Flower deserializa cada tensor con `np.load(BytesIO(bytes))`
 * independientemente de lo que diga `tensor_type`, así que el cliente Android
 * tiene que emitir ficheros `.npy` válidos y no floats crudos.
 *
 * El intercambio FedPer usa UN ÚNICO tensor 1-D: el vector plano del encoder.
 * Del lado de Python eso es un `ndarray` de forma `(encoder_flat_size,)`, que
 * FedAvg promedia elemento a elemento igual que promediaría quince tensores
 * sueltos. Se evita así tener que acordar el emparejamiento entre nombres de
 * firma de TFLite y el orden de `model.weights` de Keras.
 *
 * Todo se escribe en little-endian explícito (`<f4`): aunque las ABI de
 * Android en uso lo sean, depender del orden nativo haría que un fallo sólo
 * apareciese en un dispositivo concreto.
 */
object NpyHelper {

    private const val MAGIC_LENGTH = 6
    private val MAGIC = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
        'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())

    /** Empaqueta un vector como `.npy` 1-D de float32. */
    fun toNpy(values: FloatArray): ByteBuffer {
        val dict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${values.size},), }"
        // La especificación exige que (10 + longitud de cabecera) sea múltiplo
        // de 64 para que el payload quede alineado.
        val unpadded = 10 + dict.length + 1
        val padding = (64 - unpadded % 64) % 64
        val header = dict + " ".repeat(padding) + "\n"

        val buffer = ByteBuffer
            .allocate(10 + header.length + values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(MAGIC)
        buffer.put(1)  // versión mayor
        buffer.put(0)  // versión menor
        buffer.putShort(header.length.toShort())
        buffer.put(header.toByteArray(Charsets.US_ASCII))
        for (v in values) buffer.putFloat(v)

        buffer.rewind()
        return buffer
    }

    /**
     * Extrae los float32 de un `.npy`.
     *
     * Acepta también un buffer sin cabecera (se interpreta como float32
     * little-endian crudo), lo que permite tolerar un servidor que envíe los
     * pesos sin envolver.
     */
    fun fromNpy(buffer: ByteBuffer): FloatArray {
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = source.position()

        if (source.remaining() < 10 || !hasMagic(source, start)) {
            source.position(start)
            return readFloats(source)
        }

        source.position(start + MAGIC_LENGTH)
        val major = source.get().toInt()
        source.get() // versión menor, no se usa
        val headerLength = if (major == 1) {
            source.short.toInt() and 0xFFFF
        } else {
            source.int
        }
        val dataOffset = start + (if (major == 1) 10 else 12) + headerLength
        if (dataOffset > source.limit()) {
            throw IOException("Cabecera .npy inconsistente: los datos empezarían en $dataOffset")
        }
        source.position(dataOffset)
        return readFloats(source)
    }

    private fun hasMagic(buffer: ByteBuffer, start: Int): Boolean {
        for (i in MAGIC.indices) {
            if (buffer.get(start + i) != MAGIC[i]) return false
        }
        return true
    }

    private fun readFloats(buffer: ByteBuffer): FloatArray {
        val count = buffer.remaining() / 4
        val out = FloatArray(count)
        buffer.asFloatBuffer().get(out)
        return out
    }
}
