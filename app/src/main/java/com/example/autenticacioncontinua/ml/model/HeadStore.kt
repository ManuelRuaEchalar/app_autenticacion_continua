package com.example.autenticacioncontinua.ml.model

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persistencia local de la cabeza personal.
 *
 * Es el equivalente on-device de `save_personal_head` /`load_personal_head`
 * de `mejor.py` (mejor.py:648-655). La cabeza (decoder + clasificador) NUNCA
 * se agrega entre clientes ni sale del dispositivo: es lo que hace del
 * esquema un FedPer y no un FedAvg puro.
 *
 * Persistirla importa por dos motivos:
 *  - Sin ella, cada arranque de la app reiniciaría el clasificador a pesos
 *   aleatorios y el usuario volvería a la casilla de salida.
 *  - Con ella, cada ronda federada continúa la personalización donde la dejó
 *   la anterior, que es exactamente lo que hace la simulación al recargar el
 *   `.npz` del cliente al principio de cada `fit`.
 *
 * Formato: float32 little-endian crudo, sin cabecera. La longitud tiene que
 * coincidir con `head_flat_size` del manifiesto; si no, el fichero se
 * descarta y la cabeza arranca de su inicialización aleatoria.
 */
class HeadStore(context: Context, private val expectedSize: Int) {

    private val file = File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    fun exists(): Boolean = file.exists()

    /** @return los pesos guardados, o `null` si no hay o son incompatibles. */
    fun load(): FloatArray? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size != expectedSize * 4) {
                Log.w(
                    TAG,
                    "Cabeza guardada de ${bytes.size / 4} floats, se esperaban " +
                        "$expectedSize. Se descarta (¿cambió la arquitectura?)."
                )
                file.delete()
                return null
            }
            val out = FloatArray(expectedSize)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            out
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer la cabeza personal: ${e.message}")
            null
        }
    }

    fun save(weights: FloatArray) {
        require(weights.size == expectedSize) {
            "Se intentó guardar una cabeza de ${weights.size} floats, se esperaban " +
                "$expectedSize"
        }
        val buffer = ByteBuffer.allocate(weights.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(weights)
        // Escritura atómica: un fallo a media escritura dejaría una cabeza
        // corrupta que se descartaría entera en el siguiente arranque.
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(buffer.array())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val TAG = "HeadStore"
        private const val DIR_NAME = "fedper"
        private const val FILE_NAME = "personal_head.bin"
    }
}
