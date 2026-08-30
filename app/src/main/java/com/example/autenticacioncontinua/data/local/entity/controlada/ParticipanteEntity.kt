package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persona que participa en el estudio controlado.
 *
 * NO SE GUARDA NINGÚN DATO IDENTIFICATIVO. El [seudonimo] lo asigna el
 * investigador (`P01`, `P02`…) y la correspondencia con la persona vive fuera
 * de la aplicación, en la carpeta del investigador. La base de datos viaja en
 * un teléfono que se presta a desconocidos: cualquier cosa que permita
 * identificar a un participante anterior no puede estar dentro.
 *
 * LAS COVARIABLES SON DE TRAMO, NO EXACTAS. Se guarda un tramo de edad y no la
 * edad, por lo mismo: con 25 participantes, una edad exacta más el sexo y la
 * lateralidad reidentifican a casi cualquiera. Sirven igual para lo que hacen
 * falta, que es describir la muestra y comprobar que no está sesgada.
 *
 * [competenciaLatin] es autoinformada. Existe porque uno de los bloques se
 * teclea en latín: un participante que lo entienda tecleará distinto —lee por
 * palabras en vez de por letras— y eso es una covariable, no ruido.
 */
@Entity(
    tableName = "participantes",
    indices = [Index(value = ["seudonimo"], unique = true)]
)
data class ParticipanteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Único. El índice lo garantiza en la base y no sólo en la interfaz: dos
     * altas del mismo participante partirían sus sesiones en dos personas
     * distintas, y en un análisis con particiones disjuntas por persona eso es
     * fuga de identidad entre entrenamiento y prueba.
     */
    val seudonimo: String,

    val fechaAltaMs: Long,

    /** `18-24` | `25-34` | `35-44` | `45-54` | `55+` | `ns` */
    val tramoEdad: String,
    /** `f` | `m` | `otro` | `ns` */
    val sexo: String,
    /** `diestra` | `zurda` | `ambidiestra` | `ns` */
    val lateralidad: String,
    /** `ninguna` | `basica` | `alta` — autoinformada. */
    val competenciaLatin: String,

    val notas: String = ""
) {
    companion object {
        const val NO_DECLARADO = "ns"

        val TRAMOS_EDAD = listOf("18-24", "25-34", "35-44", "45-54", "55+", NO_DECLARADO)
        val SEXOS = listOf("f", "m", "otro", NO_DECLARADO)
        val LATERALIDADES = listOf("diestra", "zurda", "ambidiestra", NO_DECLARADO)
        val COMPETENCIAS_LATIN = listOf("ninguna", "basica", "alta")

        /**
         * Normaliza un seudónimo antes de compararlo o guardarlo.
         *
         * Sin esto, `p01`, `P01 ` y `P01` serían tres participantes: el índice
         * único de SQLite distingue mayúsculas y no recorta espacios, así que
         * la unicidad tiene que apoyarse en una forma canónica.
         */
        fun normalizar(seudonimo: String): String = seudonimo.trim().uppercase()
    }
}
