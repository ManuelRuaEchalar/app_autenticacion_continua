package com.example.autenticacioncontinua.federated

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NpyHelper {

    /**
     * Extrae el payload (raw floats) de un archivo .npy en formato ByteBuffer.
     * Salta la cabecera de 128 bytes (o la longitud que indique) y devuelve
     * un slice del buffer original.
     */
    fun stripNpyHeader(buffer: ByteBuffer): ByteBuffer {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val pos = buffer.position()

        // 1. Check Magic String "\x93NUMPY"
        if (buffer.get() != 0x93.toByte() ||
            buffer.get() != 'N'.toByte() ||
            buffer.get() != 'U'.toByte() ||
            buffer.get() != 'M'.toByte() ||
            buffer.get() != 'P'.toByte() ||
            buffer.get() != 'Y'.toByte()
        ) {
            // Not a NPY file, return as is
            buffer.position(pos)
            return buffer
        }

        // 2. Version
        val major = buffer.get()
        val minor = buffer.get()

        // 3. Header Length
        val headerLen = if (major.toInt() == 1) {
            buffer.short.toInt()
        } else {
            buffer.int // For version 2.0+
        }

        // The data starts at 10 + headerLen (for v1) or 12 + headerLen (for v2)
        val offset = (if (major.toInt() == 1) 10 else 12) + headerLen

        // Return a sliced buffer pointing directly to the data
        buffer.position(offset)
        return buffer.slice()
    }

    /**
     * Envuelve un ByteBuffer de floats raw con una cabecera .npy estándar 1D.
     * Esto es necesario para que el servidor Flower (Python) pueda hacer np.load().
     */
    fun wrapWithNpyHeader(rawBuffer: ByteBuffer): ByteBuffer {
        val numFloats = rawBuffer.remaining() / 4

        // Crear la cadena del diccionario
        val dictString = "{'descr': '<f4', 'fortran_order': False, 'shape': ($numFloats,), }"
        
        // La longitud de la cabecera debe ser dictString.length + 1 (por el \n),
        // y (10 + dictLength) debe ser múltiplo de 64.
        var paddingLength = 64 - ((10 + dictString.length + 1) % 64)
        if (paddingLength == 64) paddingLength = 0
        
        val paddedDict = dictString + " ".repeat(paddingLength) + "\n"
        val headerLength = paddedDict.length

        val npyBuffer = ByteBuffer.allocateDirect(10 + headerLength + rawBuffer.remaining())
        npyBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Magic string
        npyBuffer.put(0x93.toByte())
        npyBuffer.put("NUMPY".toByteArray(Charsets.US_ASCII))
        
        // Version 1.0
        npyBuffer.put(1.toByte())
        npyBuffer.put(0.toByte())
        
        // Header length
        npyBuffer.putShort(headerLength.toShort())
        
        // Dictionary
        npyBuffer.put(paddedDict.toByteArray(Charsets.US_ASCII))
        
        // Payload
        val oldPos = rawBuffer.position()
        npyBuffer.put(rawBuffer)
        rawBuffer.position(oldPos)

        npyBuffer.rewind()
        return npyBuffer
    }
}
