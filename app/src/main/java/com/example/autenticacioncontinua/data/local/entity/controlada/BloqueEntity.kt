package com.example.autenticacioncontinua.data.local.entity.controlada

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un bloque de tecleo de cinco minutos dentro de una sesión.
 *
 * ES LA UNIDAD DE ANÁLISIS. El diagnóstico del 17-18/08 midió que el 85.4% de
 * la varianza entre ráfagas era de sesión y contexto, y que la correlación
 * intraepisodio (ICC 0.41-0.50) dejaba 66 ventanas en unas 2.4 observaciones
 * independientes. La consecuencia práctica es que los intervalos de confianza
 * se calculan remuestreando BLOQUES, nunca ventanas: dos ventanas del mismo
 * bloque no son dos observaciones.
 *
 * [idioma] va por bloque, no por sesión: cada sesión lleva dos bloques en
 * español y uno en latín, y la posición del de latín rota entre sesiones para
 * que el efecto del idioma no quede confundido con el del cansancio.
 *
 * [interrumpido] separa "el bloque duró menos de cinco minutos" de "el bloque
 * salió mal". Una llamada entrante o la pantalla apagándose paran el bloque; el
 * tiempo NO se falsea para que cuadre, se marca. Un bloque de 3 min 20 s
 * marcado como interrumpido es analizable; uno de 3 min 20 s presentado como
 * de cinco minutos corrompe cualquier tasa por unidad de tiempo.
 */
@Entity(
    tableName = "bloques",
    foreignKeys = [
        ForeignKey(
            entity = SesionControladaEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sesionId"])]
)
data class BloqueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val sesionId: Long,

    /** 0, 1, 2 dentro de la sesión. La aclimatación NO genera bloque. */
    val indice: Int,

    val inicioMs: Long,
    val finMs: Long = 0,

    /** `es` | `la` */
    val idioma: String,

    /** Identificadores de los párrafos mostrados, separados por coma. */
    val parrafosUsados: String = "",

    val pulsaciones: Int = 0,
    val errores: Int = 0,

    /**
     * Pulsaciones de retroceso.
     *
     * Se cuentan aparte de [errores] porque son cosas distintas: el error es
     * un carácter que no coincide con el esperado, y el borrado es la
     * corrección. Un participante que corrige mucho y otro que no corrige nada
     * pueden tener la misma precisión final y una dinámica de tecleo
     * completamente distinta.
     */
    val borrados: Int = 0,

    /** Palabras por minuto, con la convención de 5 caracteres = 1 palabra. */
    val ppm: Float = 0f,
    /** Aciertos / pulsaciones, en [0, 1]. */
    val precision: Float = 0f,

    val interrumpido: Boolean = false,
    val motivoInterrupcion: String = ""
) {
    val duracionMs: Long get() = if (finMs > inicioMs) finMs - inicioMs else 0L

    companion object {
        const val IDIOMA_ESPANOL = "es"
        const val IDIOMA_LATIN = "la"

        /** Cinco minutos. El bloque termina aquí, corte donde corte el texto. */
        const val DURACION_MS = 5 * 60 * 1000L

        const val BLOQUES_POR_SESION = 3
    }
}
