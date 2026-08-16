package com.example.autenticacioncontinua.domain.ml

import com.example.autenticacioncontinua.domain.model.LabeledSession

/**
 * Qué tramos del histórico NO son del dueño del teléfono.
 *
 * EL FALLO QUE ESTO EVITA. `WindowSegmenter` lee `accelerometer_data` de los
 * últimos 14 días y lo trata todo como material genuino del usuario. En cuanto
 * la app permite grabar a un impostor en el mismo aparato, esas muestras caen
 * en la misma tabla y el modelo personal se entrenaría con datos de otra
 * persona etiquetados como propios. El fallo es SILENCIOSO: no hay excepción ni
 * síntoma en la pantalla, sólo un modelo que empeora sin causa aparente y unas
 * métricas que ya no significan lo que dicen.
 *
 * POR QUÉ SE FILTRAN MUESTRAS CRUDAS Y NO VENTANAS. Quitar las muestras antes
 * de segmentar deja un hueco de al menos la duración de la ráfaga (3 min) allí
 * donde estaba el impostor. Como ese hueco supera con creces
 * `DEFAULT_SESSION_GAP_MS` (30 s), `detectSessions` corta ahí por su cuenta y
 * ninguna sesión queda a caballo entre dos personas. Filtrando ventanas ya
 * construidas, en cambio, la interpolación habría podido tender un puente entre
 * las muestras de uno y las del otro antes de que hubiera nada que descartar.
 */
object ExclusionEtiquetada {

    /**
     * Intervalos a excluir, ordenados y fusionados.
     *
     * Sólo los de OTRAS personas: las ráfagas de control del propio dueño son
     * datos genuinos suyos y deben seguir entrenando su modelo. Ver
     * `LabeledSessionEntity.isOwner`.
     */
    fun intervalos(sesiones: List<LabeledSession>): List<LongRange> {
        val crudos = sesiones
            .filterNot { it.isOwner }
            .map { it.startMs..it.effectiveEndMs }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }

        if (crudos.isEmpty()) return emptyList()

        // Fusionar solapamientos: dos ráfagas no deberían solaparse nunca —el
        // gestor de sesión no permite dos capturas a la vez—, pero un tramo
        // abierto se extiende 15 min por precaución y sí puede tragarse al
        // siguiente. Sin fusionar, la búsqueda binaria de [contiene] sobre una
        // lista con solapes puede caer en el intervalo equivocado.
        val fusionados = ArrayList<LongRange>()
        var actual = crudos.first()
        for (r in crudos.drop(1)) {
            actual = if (r.first <= actual.last) {
                actual.first..maxOf(actual.last, r.last)
            } else {
                fusionados.add(actual)
                r
            }
        }
        fusionados.add(actual)
        return fusionados
    }

    /**
     * Si `t` cae dentro de alguno de los [intervalos].
     *
     * Búsqueda binaria: se invoca una vez por muestra y el histórico de un
     * participante son del orden de un millón de filas por sensor.
     * [intervalos] debe venir de [intervalos] (ordenado y sin solapes).
     */
    fun contiene(intervalos: List<LongRange>, t: Long): Boolean {
        if (intervalos.isEmpty()) return false
        var lo = 0
        var hi = intervalos.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = intervalos[mid]
            when {
                t < r.first -> hi = mid - 1
                t > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
