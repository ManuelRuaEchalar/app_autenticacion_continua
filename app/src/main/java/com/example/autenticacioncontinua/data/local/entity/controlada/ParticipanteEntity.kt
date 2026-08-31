package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persona que participa en el estudio controlado.
 *
 * NO SE GUARDA NINGÚN DATO DE LA PERSONA. Sólo un [seudonimo] que asigna el
 * investigador (`P01`, `P02`…), y la correspondencia con la persona vive fuera
 * de la aplicación, en papel, en la carpeta del investigador. La base de datos
 * viaja en un teléfono que se presta a desconocidos: cualquier cosa que permita
 * identificar a un participante anterior no puede estar dentro.
 *
 * ### Por qué ya no hay covariables (decisión del 30/08)
 *
 * La versión anterior guardaba tramo de edad, sexo, lateralidad y competencia en
 * latín. Se eliminaron de la tabla, no sólo del formulario.
 *
 * El motivo lo daba ya la nota del 23/08 sobre por qué las covariables se pedían
 * POR TRAMOS: con 20-30 participantes, edad, sexo y lateralidad juntas
 * reidentifican a casi cualquiera. Redondear los tramos reducía el riesgo pero no
 * lo quitaba, y la única forma de que un dato no se filtre es que no exista.
 * Guardarlos «por si acaso» era coste de privacidad sin uso previsto.
 *
 * CONSECUENCIA QUE HAY QUE DECLARAR EN LA MEMORIA, porque afecta al análisis y no
 * sólo al formulario:
 *
 * - la muestra ya no se puede DESCRIBIR («n participantes, x% zurdos, edad media
 *   tal»), que es una tabla habitual en la sección de método, ni comprobar con
 *   datos que no esté sesgada;
 * - la lateralidad no puede entrar como covariable ni como control, pese a ser
 *   plausible que afecte a la motricidad del tecleo;
 * - `competenciaLatin` era la mitigación declarada del riesgo residual «alguien
 *   que estudió latín o sabe italiano tiene ventaja» en el bloque de latín. Sin
 *   ella, ese riesgo pasa de mitigado a sólo declarado.
 *
 * Si alguna de esas tres cosas hiciera falta después, el sitio correcto es el
 * cuaderno de campo en papel, junto a la correspondencia persona ↔ seudónimo, no
 * el teléfono.
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

    /**
     * Cuándo lo dio de alta el investigador.
     *
     * No es un dato de la persona sino del registro: sirve para ordenar la lista
     * y para casar un alta con la página del cuaderno de campo.
     */
    val fechaAltaMs: Long
) {
    companion object {
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
