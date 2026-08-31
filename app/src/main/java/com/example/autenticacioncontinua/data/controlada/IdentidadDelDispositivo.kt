package com.example.autenticacioncontinua.data.controlada

import android.content.Context

/**
 * Cuál de los dos terminales del estudio es éste: `A` o `B`.
 *
 * POR QUÉ NO SE DERIVA DEL MODELO NI DEL NÚMERO DE SERIE. Porque la etiqueta
 * `A`/`B` es del PROTOCOLO, no del aparato: es la que aparece en el cuaderno de
 * campo y la que usa `PlanDeDispositivos` para decidir a quién le toca cuál. Si
 * un terminal se rompiera y hubiera que sustituirlo, el repuesto tiene que
 * poder heredar la etiqueta del que sustituye, o la secuencia alternada de
 * todos los participantes se rompería a mitad del estudio.
 *
 * Se fija UNA VEZ por terminal, en la lista de verificación previa, y no se
 * vuelve a tocar. El valor real de cada sesión se guarda de todos modos en
 * `sesiones_controladas.dispositivoId`, así que aunque alguien lo cambiara por
 * error, lo ya recogido conserva la etiqueta con la que se recogió.
 *
 * ES UNA INTERFAZ porque quien la consume es un ViewModel que se prueba en la
 * JVM, y la implementación real necesita un `Context` de Android.
 */
interface IdentidadDelDispositivo {

    var etiqueta: String

    /**
     * `true` mientras nadie haya dicho qué terminal es éste.
     *
     * La lista de verificación previa lo comprueba y BLOQUEA el inicio: empezar
     * sin etiqueta dejaría todas las sesiones marcadas como `?` y el diseño
     * cruzado persona × dispositivo —que es la razón de ser de los dos
     * aparatos— no se podría analizar.
     */
    val sinAsignar: Boolean get() = etiqueta == SIN_ASIGNAR

    companion object {
        const val SIN_ASIGNAR = "?"
        val ETIQUETAS_VALIDAS = listOf("A", "B")
    }
}

/** La de verdad: persiste en las preferencias del terminal. */
class IdentidadEnPreferencias(context: Context) : IdentidadDelDispositivo {

    private val prefs =
        context.getSharedPreferences("identidad_dispositivo", Context.MODE_PRIVATE)

    override var etiqueta: String
        get() = prefs.getString(CLAVE, IdentidadDelDispositivo.SIN_ASIGNAR)
            ?: IdentidadDelDispositivo.SIN_ASIGNAR
        set(valor) {
            require(valor in IdentidadDelDispositivo.ETIQUETAS_VALIDAS) {
                "etiqueta '$valor': debe ser una de " +
                    "${IdentidadDelDispositivo.ETIQUETAS_VALIDAS}"
            }
            prefs.edit().putString(CLAVE, valor).apply()
        }

    private companion object {
        const val CLAVE = "etiqueta"
    }
}
