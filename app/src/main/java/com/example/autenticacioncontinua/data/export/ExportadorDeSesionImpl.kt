package com.example.autenticacioncontinua.data.export

import android.content.Context
import android.util.Log
import com.example.autenticacioncontinua.domain.export.IExportadorDeSesion
import com.example.autenticacioncontinua.domain.export.Manifiesto
import com.example.autenticacioncontinua.domain.export.PaqueteDeSesion
import com.example.autenticacioncontinua.domain.export.TablasDeSesion
import com.example.autenticacioncontinua.domain.export.VerificacionDePaquete
import com.example.autenticacioncontinua.domain.repository.IParticipanteRepository
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Escribe el paquete de una visita y sabe volver a leerlo.
 *
 * ### Dónde queda el fichero, y por qué ahí
 *
 * En `getExternalFilesDir(EXPORTACIONES)`, no en la caché. Son dos decisiones:
 *
 * - **No en `cacheDir`**, que es donde va la exportación de la base entera,
 *   porque Android borra la caché por su cuenta cuando el disco aprieta. Para
 *   un fichero que se comparte al momento eso da igual; para el único registro
 *   de una visita que quizá no se copie al PC hasta el viernes, es perder
 *   datos sin aviso.
 * - **En almacenamiento externo de la app**, que es visible por USB (MTP) sin
 *   root ni `adb`, para que la copia semanal del protocolo sea arrastrar una
 *   carpeta. En `filesDir` estaría a salvo pero sólo accesible con `run-as`, y
 *   un procedimiento de respaldo que exige depuración USB no se cumple.
 *
 * LOS PAQUETES NO SE BORRAN AL EXPORTAR OTRO. Es la diferencia con la
 * exportación de la base entera, que limpia su carpeta para no acumular
 * decenas de MB. Aquí cada fichero pesa un par de MB y es la única copia de una
 * visita irrepetible: acumular diez por participante es exactamente lo que se
 * quiere.
 */
class ExportadorDeSesionImpl(
    private val context: Context,
    private val sesiones: ISesionControladaRepository,
    private val participantes: IParticipanteRepository
) : IExportadorDeSesion {

    override suspend fun exportar(sesionId: Long): Result<PaqueteDeSesion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sesion = requireNotNull(sesiones.sesion(sesionId)) {
                    "no existe la sesion $sesionId"
                }
                val participante = requireNotNull(participantes.porId(sesion.participanteId)) {
                    "la sesion $sesionId apunta a un participante que ya no esta"
                }
                val bloques = sesiones.bloquesDe(sesionId)
                check(bloques.isNotEmpty()) { "la sesion $sesionId no tiene bloques" }

                val eventos = bloques.associate { it.id to sesiones.eventosDe(it.id) }
                val muestras = bloques.associate { it.id to sesiones.muestrasDe(it.id) }

                val tablas = TablasDeSesion.de(
                    sesion = sesion,
                    seudonimo = participante.seudonimo,
                    bloques = bloques,
                    eventos = eventos,
                    muestras = muestras
                )

                val visita = sesion.ordenDispositivo
                val marca = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    EXPORTACIONES
                ).apply { mkdirs() }
                val zip = File(dir, Manifiesto.nombreDelPaquete(participante.seudonimo, visita, marca))

                val manifiesto = Manifiesto.json(
                    seudonimo = participante.seudonimo,
                    sesionId = sesion.id,
                    visita = visita,
                    dispositivoId = sesion.dispositivoId,
                    versionApp = sesion.versionApp,
                    versionProtocolo = sesion.versionProtocolo,
                    exportadoMs = System.currentTimeMillis(),
                    tablas = tablas
                )

                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    out.setLevel(Deflater.BEST_COMPRESSION)
                    // El manifiesto va PRIMERO para que un zip truncado se note
                    // al abrirlo: si falta, no hay con que comprobar nada.
                    escribir(out, Manifiesto.NOMBRE, manifiesto)
                    for ((nombre, contenido) in tablas) escribir(out, nombre, contenido)
                }

                val paquete = PaqueteDeSesion(
                    fichero = zip,
                    huella = Manifiesto.huella(zip.readBytes()),
                    bytes = zip.length(),
                    filasPorTabla = tablas.mapValues { (_, c) -> c.count { it == '\n' } - 1 }
                )
                Log.i(
                    TAG,
                    "Sesion $sesionId exportada: ${zip.name}, ${paquete.bytes / 1024} KB, " +
                        "sha256 ${paquete.huellaCorta}, filas ${paquete.filasPorTabla}"
                )
                paquete
            }.onFailure { Log.e(TAG, "Fallo la exportacion de la sesion $sesionId", it) }
        }

    override suspend fun verificar(fichero: File): Result<VerificacionDePaquete> =
        withContext(Dispatchers.IO) {
            runCatching {
                val intactas = mutableListOf<String>()
                val corruptas = mutableListOf<String>()
                val filas = mutableMapOf<String, Int>()

                ZipFile(fichero).use { zip ->
                    val entradaManifiesto = requireNotNull(zip.getEntry(Manifiesto.NOMBRE)) {
                        "el paquete no trae ${Manifiesto.NOMBRE}"
                    }
                    val json = JSONObject(
                        zip.getInputStream(entradaManifiesto).bufferedReader().readText()
                    )
                    val tablas = json.getJSONArray("tablas")
                    for (i in 0 until tablas.length()) {
                        val t = tablas.getJSONObject(i)
                        val nombre = t.getString("fichero")
                        val esperada = t.getString("sha256")
                        val entrada = zip.getEntry(nombre)
                        if (entrada == null) { corruptas += nombre; continue }
                        val contenido = zip.getInputStream(entrada).bufferedReader().readText()
                        if (Manifiesto.huella(contenido) == esperada) intactas += nombre
                        else corruptas += nombre
                        filas[nombre] = contenido.count { it == '\n' } - 1
                    }
                }

                VerificacionDePaquete(
                    huella = Manifiesto.huella(fichero.readBytes()),
                    tablasIntactas = intactas,
                    tablasCorruptas = corruptas,
                    filasPorTabla = filas
                )
            }.onFailure { Log.e(TAG, "No se pudo verificar ${fichero.name}", it) }
        }

    private fun escribir(out: ZipOutputStream, nombre: String, contenido: String) {
        out.putNextEntry(ZipEntry(nombre))
        out.write(contenido.toByteArray(Charsets.UTF_8))
        out.closeEntry()
    }

    private companion object {
        const val TAG = "ExportSesion"

        /** Subcarpeta bajo el almacenamiento externo de la app. */
        const val EXPORTACIONES = "exportaciones"
    }
}
