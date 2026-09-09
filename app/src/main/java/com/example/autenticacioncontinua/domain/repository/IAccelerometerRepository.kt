package com.example.autenticacioncontinua.domain.repository

import com.example.autenticacioncontinua.domain.ml.SerieTriaxial
import com.example.autenticacioncontinua.domain.model.AccelerometerData

interface IAccelerometerRepository {
    suspend fun saveAccelerometerData(data: List<AccelerometerData>)
    suspend fun getAccelerometerDataByDate(dateString: String): List<AccelerometerData>
    suspend fun getAllAccelerometerData(): List<AccelerometerData>

    /**
     * La serie de lecturas desde `sinceMs` (epoch en milisegundos), en orden
     * ascendente, ya sin las muestras que `excluir` rechace.
     *
     * SUSTITUYE A `getAccelerometerDataSince`, QUE SE HA RETIRADO. Aquella
     * devolvia la lista entera de objetos y es, literalmente, la que agoto el
     * monton la noche del 06/09: con dos semanas de historico —1 389 630
     * filas— la sesion federada murio con `OutOfMemoryError` dentro de
     * `prepareDataset`, antes de llegar a abrir el canal gRPC. El contrato
     * cambia por eso, no por gusto: quien necesita el historico completo no
     * puede recibirlo como lista de objetos.
     *
     * EL FILTRO SE APLICA DENTRO, NO DESPUES. Es la diferencia entre no
     * reservar sitio para las muestras que se van a tirar y reservarlo para
     * tirarlas luego. `excluir` recibe el instante de cada muestra y devuelve
     * `true` si esa muestra NO debe entrar; por defecto no excluye nada.
     */
    suspend fun serieDesde(
        sinceMs: Long,
        excluir: (Long) -> Boolean = { false }
    ): SerieTriaxial

    /** Borra las lecturas anteriores a `cutoffMs`. Devuelve las filas borradas. */
    suspend fun deleteAccelerometerDataOlderThan(cutoffMs: Long): Int
}
