package com.example.autenticacioncontinua.data.local.dao.controlada

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity

@Dao
interface BloqueDao {

    @Insert
    suspend fun insertar(bloque: BloqueEntity): Long

    @Query("SELECT * FROM bloques WHERE sesionId = :sesionId ORDER BY indice")
    suspend fun de(sesionId: Long): List<BloqueEntity>

    @Query("SELECT * FROM bloques WHERE id = :id")
    suspend fun porId(id: Long): BloqueEntity?

    /**
     * Párrafos que este participante ya ha transcrito, en TODAS sus visitas.
     *
     * Es lo que permite cumplir la regla 2.6 del plan: un participante no repite
     * párrafo entre sus propias sesiones. Sin esta consulta la unicidad sería
     * sólo intra-sesión, y a partir de la segunda visita la selección volvería a
     * ofrecer textos ya vistos — un texto conocido se teclea distinto que uno
     * nuevo, así que la familiaridad con el material quedaría confundida con el
     * número de sesión.
     *
     * Devuelve la columna cruda, con los identificadores separados por coma tal
     * y como los escribe `cerrarBloque`; el repositorio la despieza. Se filtran
     * las cadenas vacías en SQL para no traer filas que no aportan nada: en un
     * participante con diez visitas son treinta filas, pero las vacías serían
     * ruido puro.
     *
     * NOTA: la unicidad es POR PARTICIPANTE y no global, y es deliberado. Dos
     * personas distintas sí pueden ver el mismo texto, y conviene que lo vean:
     * si cada una transcribiera textos distintos, la dificultad del texto
     * quedaría confundida con la persona.
     */
    @Query(
        "SELECT b.parrafosUsados FROM bloques b " +
            "JOIN sesiones_controladas s ON s.id = b.sesionId " +
            "WHERE s.participanteId = :participanteId AND b.parrafosUsados != ''"
    )
    suspend fun parrafosUsadosPor(participanteId: Long): List<String>

    @Query(
        "UPDATE bloques SET finMs = :finMs, pulsaciones = :pulsaciones, errores = :errores, " +
            "borrados = :borrados, ppm = :ppm, precision = :precision, " +
            "parrafosUsados = :parrafos, interrumpido = :interrumpido, " +
            "motivoInterrupcion = :motivo WHERE id = :id"
    )
    suspend fun cerrar(
        id: Long,
        finMs: Long,
        pulsaciones: Int,
        errores: Int,
        borrados: Int,
        ppm: Float,
        precision: Float,
        parrafos: String,
        interrumpido: Boolean,
        motivo: String
    )

    // ------------------------------------------------------------------
    // Muestras inerciales
    // ------------------------------------------------------------------

    /**
     * Inserción por lotes. Room la envuelve en UNA transacción.
     *
     * Es la diferencia entre poder escribir a 100 Hz y no poder: fila a fila,
     * cada inserción abre y cierra su propia transacción, con su `fsync`. A
     * 100 Hz por tres sensores eso es inviable mientras el participante teclea
     * — y el coste caería además dentro de la medición de consumo, que es una
     * variable dependiente del estudio.
     */
    @Insert
    suspend fun insertarMuestras(muestras: List<MuestraInercialEntity>)

    @Query("SELECT * FROM muestras_inerciales WHERE bloqueId = :bloqueId ORDER BY tMonotonoNs")
    suspend fun muestrasDe(bloqueId: Long): List<MuestraInercialEntity>

    @Query("SELECT COUNT(*) FROM muestras_inerciales WHERE bloqueId = :bloqueId")
    suspend fun cuantasMuestras(bloqueId: Long): Int

    /**
     * Tasa efectiva de muestreo del bloque, en Hz.
     *
     * Se calcula con el reloj MONÓTONO y no con el de pared. Comprobar que sale
     * cerca de 100 es la verificación de que el sensor entregó lo que se le
     * pidió: Android trata la tasa como una sugerencia, y un terminal que
     * entregue 50 Hz sin avisar cambiaría el contenido de frecuencia de las
     * ventanas sin que nada fallara.
     */
    @Query(
        "SELECT CASE WHEN MAX(tMonotonoNs) > MIN(tMonotonoNs) " +
            "THEN (COUNT(*) - 1) * 1000000000.0 / (MAX(tMonotonoNs) - MIN(tMonotonoNs)) " +
            "ELSE NULL END FROM muestras_inerciales WHERE bloqueId = :bloqueId"
    )
    suspend fun tasaEfectivaHz(bloqueId: Long): Double?

    // ------------------------------------------------------------------
    // Eventos de tecleo
    // ------------------------------------------------------------------

    @Insert
    suspend fun insertarEventos(eventos: List<EventoTecleoEntity>)

    @Query("SELECT * FROM eventos_tecleo WHERE bloqueId = :bloqueId ORDER BY tDownMs")
    suspend fun eventosDe(bloqueId: Long): List<EventoTecleoEntity>

    @Query("SELECT COUNT(*) FROM eventos_tecleo WHERE bloqueId = :bloqueId")
    suspend fun cuantosEventos(bloqueId: Long): Int
}
