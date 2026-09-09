package com.example.autenticacioncontinua.data.repository

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.local.entity.toDomain
import com.example.autenticacioncontinua.data.local.entity.toEntity
import com.example.autenticacioncontinua.domain.model.AccelerometerData
import com.example.autenticacioncontinua.domain.ml.SerieTriaxial
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccelerometerRepositoryImpl(
    private val db: AppDatabase
) : IAccelerometerRepository {

    override suspend fun saveAccelerometerData(data: List<AccelerometerData>) {
        withContext(Dispatchers.IO) {
            val entities = data.map { it.toEntity() }
            db.accelerometerDao().insertAll(entities)
        }
    }

    override suspend fun getAccelerometerDataByDate(dateString: String): List<AccelerometerData> {
        return withContext(Dispatchers.IO) {
            db.accelerometerDao().getByDate(dateString, limit = 500, offset = 0).map { it.toDomain() }
        }
    }

    override suspend fun getAllAccelerometerData(): List<AccelerometerData> {
        return withContext(Dispatchers.IO) {
            db.accelerometerDao().getAll().map { it.toDomain() }
        }
    }

    /**
     * Ver [IAccelerometerRepository.serieDesde].
     *
     * SE LEE POR BLOQUES DE [TAM_BLOQUE] Y NO DE UNA VEZ. En cada vuelta viven
     * como objetos 20 000 filas —unos pocos megas— y de ahi pasan a los arrays
     * primitivos de la serie; la lista del bloque queda para el recolector
     * antes de pedir el siguiente. Es lo que hace que el pico de memoria
     * dependa del tamano del bloque y no del historico.
     *
     * El bloque siguiente se pide por (timestamp, id) del ultimo leido, no por
     * `OFFSET`: ver [AccelerometerDao.getBloqueDesde].
     */
    override suspend fun serieDesde(
        sinceMs: Long,
        excluir: (Long) -> Boolean
    ): SerieTriaxial = withContext(Dispatchers.IO) {
        val dao = db.accelerometerDao()
        // El COUNT dimensiona la serie de una vez. Es una cota superior —el
        // filtro puede quitar muestras— pero nunca inferior, que es lo que
        // importa para no tener que copiar los arrays al crecer.
        val serie = SerieTriaxial(dao.contarDesde(sinceMs))
        var ultimoTs = Long.MIN_VALUE
        var ultimoId = Long.MIN_VALUE
        while (true) {
            val bloque = dao.getBloqueDesde(sinceMs, ultimoTs, ultimoId, TAM_BLOQUE)
            if (bloque.isEmpty()) break
            for (fila in bloque) {
                if (!excluir(fila.timestamp)) {
                    serie.anadir(fila.timestamp, fila.x, fila.y, fila.z)
                }
            }
            val ultima = bloque.last()
            ultimoTs = ultima.timestamp
            ultimoId = ultima.id
        }
        serie
    }

    override suspend fun deleteAccelerometerDataOlderThan(cutoffMs: Long): Int {
        return withContext(Dispatchers.IO) {
            db.accelerometerDao().deleteOlderThan(cutoffMs)
        }
    }

    private companion object {
        /**
         * Filas por peticion.
         *
         * 20 000 muestras son unos 400 s de sensor a 50 Hz y menos de 2 MB de
         * objetos vivos. Mas grande no acelera —el coste esta en el recorrido
         * del indice, no en el numero de peticiones— y acerca el pico de
         * memoria justo a lo que este cambio existe para evitar.
         */
        const val TAM_BLOQUE = 20_000
    }
}
