package com.example.autenticacioncontinua.data.export

import android.content.Context
import android.net.Uri
import com.example.autenticacioncontinua.domain.export.IDataExportService
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataExportServiceImpl(
    private val context: Context,
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
}
