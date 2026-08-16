package com.example.autenticacioncontinua.domain.export

import android.net.Uri
import java.io.File

interface IDataExportService {

    /**
     * Volcado a CSV de acelerómetro y giroscopio.
     *
     * NO USAR PARA RECOGER A UN PARTICIPANTE. Carga las dos tablas enteras en
     * memoria (`getAll()`), y un histórico de dos días son del orden de un
     * millón de entidades con su `String` de fecha cada una: en un teléfono de
     * gama media eso no cabe en el heap. El propio `AccelerometerDao` ya avisa
     * de ello en el comentario de `getAll`. Se conserva porque sigue sirviendo
     * para inspeccionar históricos pequeños a mano.
     *
     * Para la recogida de campo, [exportDatabaseZip].
     */
    suspend fun exportToCsv(uri: Uri): Result<Unit>

    /**
     * Copia consistente y comprimida de la base, lista para compartir.
     *
     * Es la vía de recogida del corpus de fondo, y no el CSV, por cuatro
     * razones medidas:
     *
     *  1. `generar_pool_par.py` ya consume SQLite directamente
     *     (`sqlite3.connect`, tablas `accelerometer_data` / `gyroscope_data`):
     *     la base no necesita ninguna conversión.
     *  2. Va el DIARIO DE EVENTOS dentro. Es la única forma de saber si el
     *     servicio de un participante siguió vivo los dos días o el sistema lo
     *     mató la primera noche; sin él, una base con pocos datos es ambigua.
     *  3. Ocupa la mitad: el CSV escribe la marca de tiempo tres veces por
     *     fila (`timestamp`, `dateString` y la formateada).
     *  4. No depende de la configuración regional del teléfono del
     *     participante, que es de lo que sí depende el `SimpleDateFormat` del
     *     CSV.
     *
     * @param participantId seudónimo, para que el fichero llegue identificado
     *   cuando son doce.
     * @return el zip en la caché, apto para `FileProvider`.
     */
    suspend fun exportDatabaseZip(participantId: String): Result<File>
}
