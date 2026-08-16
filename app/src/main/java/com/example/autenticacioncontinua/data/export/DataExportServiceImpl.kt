package com.example.autenticacioncontinua.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.domain.export.IDataExportService
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataExportServiceImpl(
    private val context: Context,
    private val database: AppDatabase,
    private val accelerometerRepository: IAccelerometerRepository,
    private val gyroscopeRepository: IGyroscopeRepository
) : IDataExportService {

    override suspend fun exportToCsv(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open OutputStream for given URI"))

            val writer = OutputStreamWriter(outputStream)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

            writer.use { out ->
                // Write Header
                out.write("Sensor,Timestamp,DateString,FormattedTime,X,Y,Z\n")

                // Fetch and write Accelerometer Data
                val accelData = accelerometerRepository.getAllAccelerometerData()
                for (data in accelData) {
                    val formattedTime = sdf.format(Date(data.timestamp))
                    out.write("Accelerometer,${data.timestamp},${data.dateString},${formattedTime},${data.x},${data.y},${data.z}\n")
                }

                // Fetch and write Gyroscope Data
                val gyroData = gyroscopeRepository.getAllGyroscopeData()
                for (data in gyroData) {
                    val formattedTime = sdf.format(Date(data.timestamp))
                    out.write("Gyroscope,${data.timestamp},${data.dateString},${formattedTime},${data.x},${data.y},${data.z}\n")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportDatabaseZip(
        participantId: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // PASO OBLIGATORIO. Room abre la base en modo WAL, así que las
            // escrituras recientes viven en `<base>-wal` y no en el fichero
            // principal. Copiar el `.db` sin este checkpoint entrega una base
            // a la que le faltan justo las últimas horas de recolección, que
            // son las que más interesan. TRUNCATE vuelca el WAL al fichero
            // principal y lo deja a cero, con lo que el `.db` basta por sí
            // solo.
            //
            // No se usa `VACUUM INTO`, que sería más limpio, porque exige
            // SQLite 3.27 (Android 11 / API 30) y aquí `minSdk` es 24.
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }

            val dbFile = context.getDatabasePath(DB_NAME)
            check(dbFile.exists()) { "No existe la base $DB_NAME" }

            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            // Cada exportación ronda las decenas de MB. Sin limpiar, doce
            // intentos dejan la caché del participante llena y Android empieza
            // a borrar por su cuenta lo que le parece.
            dir.listFiles()?.forEach { it.delete() }

            val marca = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val etiqueta = participantId.trim()
                .ifBlank { "sin_id" }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            val zip = File(dir, "bd_${etiqueta}_$marca.zip")

            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                // El WAL y el SHM se incluyen sólo si el checkpoint no los dejó
                // vacíos. No debería ocurrir, pero si ocurre es preferible
                // llevárselos que descubrir después que faltan muestras.
                listOf(
                    dbFile,
                    File(dbFile.path + "-wal"),
                    File(dbFile.path + "-shm")
                ).filter { it.exists() && it.length() > 0 }.forEach { f ->
                    out.putNextEntry(ZipEntry(f.name))
                    f.inputStream().buffered().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }

            Log.i(
                TAG,
                "Base exportada: ${zip.name}, ${zip.length() / 1024} KB " +
                    "(origen ${dbFile.length() / 1024} KB)"
            )
            zip
        }.onFailure { Log.e(TAG, "Falló la exportación de la base", it) }
    }

    private companion object {
        const val TAG = "DataExport"

        /** Debe coincidir con el nombre que da `appModule` a `Room.databaseBuilder`. */
        const val DB_NAME = "continuous_auth_db"

        /** Subcarpeta de `cacheDir` publicada en `res/xml/file_paths.xml`. */
        const val SHARE_DIR = "compartir"
    }
}
