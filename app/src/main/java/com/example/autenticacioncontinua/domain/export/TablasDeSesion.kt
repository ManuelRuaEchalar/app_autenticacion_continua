package com.example.autenticacioncontinua.domain.export

import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity

/**
 * Las cuatro tablas de una visita, en CSV.
 *
 * ### El seudónimo va, el identificador interno NO
 *
 * Es la regla de esta clase y la razón de que exista en vez de volcar las
 * entidades tal cual. `sesiones_controladas.participanteId` es la clave de la
 * fila de `participantes` en el teléfono; fuera de ese teléfono no significa
 * nada, y peor: es un número correlativo por orden de alta, o sea que revela el
 * ORDEN EN QUE SE RECLUTÓ a la gente. Con veinte personas y una libreta de
 * campo, eso reidentifica.
 *
 * Lo que sale es el seudónimo, que es lo único que el estudio necesita para
 * juntar las visitas de una misma persona, y lo único que el protocolo permite
 * que salga del aparato.
 *
 * ### Los identificadores internos que SÍ salen
 *
 * `bloqueId` y `sesionId` se conservan porque son lo que ata cada muestra
 * inercial a su bloque y cada bloque a su visita: sin ellos, cuatro CSV sueltos
 * no se pueden volver a juntar. No dicen nada de nadie —son correlativos de
 * bloque, no de persona— y se quedan dentro del propio paquete.
 */
object TablasDeSesion {

    /**
     * Nombre del fichero -> contenido, para las cuatro tablas de una visita.
     *
     * Se devuelven juntas y en un mapa, no como cuatro funciones sueltas,
     * porque el manifiesto tiene que recorrerlas todas para firmarlas y una
     * tabla que se olvidara de añadir ahí saldría del aparato sin firma.
     */
    fun de(
        sesion: SesionControladaEntity,
        seudonimo: String,
        bloques: List<BloqueEntity>,
        eventos: Map<Long, List<EventoTecleoEntity>>,
        muestras: Map<Long, List<MuestraInercialEntity>>
    ): Map<String, String> = mapOf(
        "sesion.csv" to sesionCsv(sesion, seudonimo),
        "bloques.csv" to bloquesCsv(bloques),
        "eventos_tecleo.csv" to eventosCsv(bloques, eventos),
        "muestras_inerciales.csv" to muestrasCsv(bloques, muestras)
    )

    // ------------------------------------------------------------------

    private fun sesionCsv(s: SesionControladaEntity, seudonimo: String): String {
        val sb = StringBuilder()
        sb.append(
            FormatoCsv.fila(
                listOf(
                    "sesionId", "seudonimo", "dispositivoId", "inicioMs", "finMs",
                    "ordenDispositivo", "semillaSeleccion", "versionApp",
                    "versionProtocolo", "bateriaInicio", "bateriaFin", "estado",
                    "motivoInvalidacion"
                )
            )
        )
        sb.append(
            FormatoCsv.fila(
                listOf(
                    FormatoCsv.celda(s.id),
                    FormatoCsv.celda(seudonimo),
                    FormatoCsv.celda(s.dispositivoId),
                    FormatoCsv.celda(s.inicioMs),
                    FormatoCsv.celda(s.finMs),
                    FormatoCsv.celda(s.ordenDispositivo),
                    FormatoCsv.celda(s.semillaSeleccion),
                    FormatoCsv.celda(s.versionApp),
                    FormatoCsv.celda(s.versionProtocolo),
                    FormatoCsv.celda(s.bateriaInicio),
                    FormatoCsv.celda(s.bateriaFin),
                    FormatoCsv.celda(s.estado),
                    FormatoCsv.celda(s.motivoInvalidacion)
                )
            )
        )
        return sb.toString()
    }

    private fun bloquesCsv(bloques: List<BloqueEntity>): String {
        val sb = StringBuilder()
        sb.append(
            FormatoCsv.fila(
                listOf(
                    "bloqueId", "sesionId", "indice", "inicioMs", "finMs", "idioma",
                    "parrafosUsados", "pulsaciones", "errores", "borrados", "ppm",
                    "precision", "interrumpido", "motivoInterrupcion"
                )
            )
        )
        for (b in bloques) {
            sb.append(
                FormatoCsv.fila(
                    listOf(
                        FormatoCsv.celda(b.id),
                        FormatoCsv.celda(b.sesionId),
                        FormatoCsv.celda(b.indice),
                        FormatoCsv.celda(b.inicioMs),
                        FormatoCsv.celda(b.finMs),
                        FormatoCsv.celda(b.idioma),
                        FormatoCsv.celda(b.parrafosUsados),
                        FormatoCsv.celda(b.pulsaciones),
                        FormatoCsv.celda(b.errores),
                        FormatoCsv.celda(b.borrados),
                        FormatoCsv.celda(b.ppm),
                        FormatoCsv.celda(b.precision),
                        FormatoCsv.celda(b.interrumpido),
                        FormatoCsv.celda(b.motivoInterrupcion)
                    )
                )
            )
        }
        return sb.toString()
    }

    private fun eventosCsv(
        bloques: List<BloqueEntity>,
        eventos: Map<Long, List<EventoTecleoEntity>>
    ): String {
        val sb = StringBuilder()
        sb.append(
            FormatoCsv.fila(
                listOf(
                    "bloqueId", "parrafoId", "posicion", "esperado", "recibido",
                    "acierto", "borrado", "tDownMs", "tUpMs", "permanenciaMs",
                    "x", "y", "presion", "area"
                )
            )
        )
        for (b in bloques) for (e in eventos[b.id].orEmpty()) {
            sb.append(
                FormatoCsv.fila(
                    listOf(
                        FormatoCsv.celda(e.bloqueId),
                        FormatoCsv.celda(e.parrafoId),
                        FormatoCsv.celda(e.posicion),
                        FormatoCsv.celda(e.esperado),
                        FormatoCsv.celda(e.recibido),
                        FormatoCsv.celda(e.acierto),
                        FormatoCsv.celda(e.borrado),
                        FormatoCsv.celda(e.tDownMs),
                        FormatoCsv.celda(e.tUpMs),
                        // Derivada y no almacenada: se calcula igual en el
                        // analisis, pero llevarla escrita evita que cada script
                        // vuelva a decidir que hacer con un tUp de cero.
                        FormatoCsv.celda(e.permanenciaMs),
                        FormatoCsv.celda(e.x),
                        FormatoCsv.celda(e.y),
                        FormatoCsv.celda(e.presion),
                        FormatoCsv.celda(e.area)
                    )
                )
            )
        }
        return sb.toString()
    }

    private fun muestrasCsv(
        bloques: List<BloqueEntity>,
        muestras: Map<Long, List<MuestraInercialEntity>>
    ): String {
        val sb = StringBuilder()
        sb.append(
            FormatoCsv.fila(
                listOf(
                    "bloqueId", "tParedMs", "tMonotonoNs",
                    "accX", "accY", "accZ", "gyrX", "gyrY", "gyrZ",
                    "magX", "magY", "magZ"
                )
            )
        )
        for (b in bloques) for (m in muestras[b.id].orEmpty()) {
            sb.append(
                FormatoCsv.fila(
                    listOf(
                        FormatoCsv.celda(m.bloqueId),
                        FormatoCsv.celda(m.tParedMs),
                        FormatoCsv.celda(m.tMonotonoNs),
                        FormatoCsv.celda(m.accX),
                        FormatoCsv.celda(m.accY),
                        FormatoCsv.celda(m.accZ),
                        FormatoCsv.celda(m.gyrX),
                        FormatoCsv.celda(m.gyrY),
                        FormatoCsv.celda(m.gyrZ),
                        FormatoCsv.celda(m.magX),
                        FormatoCsv.celda(m.magY),
                        FormatoCsv.celda(m.magZ)
                    )
                )
            )
        }
        return sb.toString()
    }
}
