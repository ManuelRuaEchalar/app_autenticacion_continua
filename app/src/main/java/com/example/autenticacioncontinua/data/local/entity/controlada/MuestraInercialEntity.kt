package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una muestra de los tres sensores inerciales, ya alineada.
 *
 * UNA FILA CON LOS TRES SENSORES, no una tabla por sensor. Es el cambio
 * respecto a la recogida ambiental, que tiene `accelerometer_data` y
 * `gyroscope_data` separadas y obliga a realinearlas por marca de tiempo en el
 * análisis. Aquí la captura es sincronizada por diseño, así que alinear en el
 * momento de escribir ahorra una unión de veintitantos millones de filas por
 * cada análisis. La tabla ambiental NO se toca: sigue como está.
 *
 * DOS RELOJES. [tParedMs] es hora de pared, para casar con el cuaderno de
 * campo y con las demás tablas; [tMonotonoNs] viene de `SensorEvent.timestamp`
 * y es el único válido para calcular intervalos entre muestras, porque el reloj
 * de pared puede saltar por NTP a mitad de un bloque de cinco minutos y
 * fabricar un hueco o un solapamiento que no existió.
 *
 * EL MAGNETÓMETRO ES NULO. Hay terminales sin él, y va a haber muestras
 * tomadas mientras el sensor aún no ha entregado su primera lectura. Un cero
 * ahí sería un campo magnético de cero, que es un valor físicamente
 * significativo y falso; el nulo dice lo que pasa.
 *
 * VOLUMEN. A 100 Hz, 15 minutos de tecleo por sesión son 90 000 filas; con 25
 * participantes y 10 sesiones, unos 22 millones por dispositivo. De ahí el
 * índice único compuesto en vez de dos: ver la nota de [Companion].
 */
@Entity(
    tableName = "muestras_inerciales",
    foreignKeys = [
        ForeignKey(
            entity = BloqueEntity::class,
            parentColumns = ["id"],
            childColumns = ["bloqueId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bloqueId", "tMonotonoNs"])]
)
data class MuestraInercialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val bloqueId: Long,

    val tParedMs: Long,
    val tMonotonoNs: Long,

    val accX: Float,
    val accY: Float,
    val accZ: Float,

    val gyrX: Float,
    val gyrY: Float,
    val gyrZ: Float,

    val magX: Float? = null,
    val magY: Float? = null,
    val magZ: Float? = null
) {
    companion object {
        /**
         * POR QUÉ UN SOLO ÍNDICE COMPUESTO `(bloqueId, tMonotonoNs)` Y NO DOS.
         *
         * El plan pedía índice por `bloque_id` y por `t_monotono_ns`. Sobre 22
         * millones de filas, cada índice adicional cuesta cientos de megas y
         * ralentiza cada inserción, y la consulta real del análisis es siempre
         * la misma: "todas las muestras de este bloque, en orden". El
         * compuesto la resuelve entera —el prefijo `bloqueId` sirve además
         * como índice de la clave ajena— mientras que un índice suelto por
         * `tMonotonoNs` no responde a ninguna consulta que se vaya a hacer: no
         * hay ningún análisis que busque un instante sin saber de qué bloque.
         */
        const val NOTA_INDICE = "indice compuesto (bloqueId, tMonotonoNs)"

        /**
         * 50 Hz. La tasa la fija el protocolo, no el código.
         *
         * Bajada de 100 a 50 el 06/09 porque el magnetómetro del terminal B
         * topa en 50 Hz y el diseño cruzado exige la misma tasa en los dos
         * aparatos. El razonamiento completo está en `HZ_CONTROLADO`.
         */
        const val HZ_OBJETIVO = 50

        /**
         * Tamaño de lote de inserción.
         *
         * Insertar fila a fila abre una transacción por fila y hace inviable
         * escribir a 100 Hz mientras el participante teclea. Con lotes de 500,
         * una transacción cada cinco segundos.
         */
        const val LOTE = 500
    }
}
