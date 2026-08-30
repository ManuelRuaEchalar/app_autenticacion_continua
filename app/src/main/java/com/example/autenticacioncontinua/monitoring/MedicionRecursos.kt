package com.example.autenticacioncontinua.monitoring

import kotlin.math.abs

/** Una lectura instantánea de los recursos, tomada por el monitor. */
data class MuestraRecursos(
    /** Reloj monótono en ms; nunca el de pared, que puede saltar. */
    val tMs: Long,
    val cargaMicroAh: Long?,
    val corrienteMicroA: Long?,
    val pssKb: Long,
    val cargando: Boolean
)

/**
 * De dónde sale la cifra de consumo de un bloque.
 *
 * NO ES UN DETALLE DE IMPLEMENTACIÓN: es parte del resultado. Medido en el
 * Redmi 23129RA5FL el 24/08, el contador de carga de ese terminal se mueve en
 * escalones de **49 370 µAh**, que es exactamente el 1% de su batería: el
 * fabricante lo deriva del porcentaje en vez de exponer un culombímetro real.
 * Con esa resolución, un bloque de cinco minutos no mueve el contador ni una
 * vez, y para juntar veinte escalones harían falta casi novecientos mAh, o sea
 * ocho horas de bloque. Inviable.
 *
 * La corriente instantánea, en cambio, sí resuelve: en ese mismo terminal dio
 * ~118 mA de media bajo carga computacional. Integrada en el tiempo da el
 * consumo del bloque.
 *
 * Dos bloques medidos por métodos distintos NO SON COMPARABLES, y por eso el
 * método viaja con cada medición y [ResumenRecursos.neto] se niega a restar
 * una línea base que no use el mismo.
 */
enum class MetodoConsumo {
    /** Diferencia entre la primera y la última lectura del contador de carga. */
    CONTADOR_DE_CARGA,

    /** Integral de la corriente instantánea por la regla del trapecio. */
    INTEGRACION_DE_CORRIENTE,

    /** Ni contador utilizable ni corriente: no hay cifra de consumo. */
    NINGUNO
}

/**
 * Por qué una medición puede no ser utilizable, o qué le pasó al contador.
 *
 * Se enumeran en vez de devolver un booleano porque en el análisis hará falta
 * saber CUÁL fue el motivo: descartar por "estaba cargando" y anotar que "el
 * contador no se movió" tienen implicaciones distintas sobre el protocolo.
 *
 * OJO: no todos invalidan. [SIN_CONTADOR_DE_CARGA] y [CONTADOR_SIN_VARIACION]
 * son NOTAS sobre el instrumento: dicen que la cifra no puede venir del
 * contador, pero si hay corriente la medición sigue valiendo por integración.
 * Ver [ResumenRecursos.esValida].
 */
enum class MotivoInvalidez {
    /**
     * Había alimentación externa conectada.
     *
     * Invalida siempre. Con el cable puesto la carga sube en vez de bajar y la
     * corriente que ve el medidor es la del cargador, no la del consumo. No
     * basta con mirar `isCharging`: MIUI corta la carga al 80% por protección
     * de batería y entonces `isCharging` devuelve false con el cable puesto
     * — comprobado en el Redmi ec56958 el 24/08, donde toda la caracterización
     * se ejecutó enchufada creyendo que no lo estaba.
     */
    CARGANDO,

    /** El terminal no expone `BATTERY_PROPERTY_CHARGE_COUNTER`. No invalida por sí solo. */
    SIN_CONTADOR_DE_CARGA,

    /**
     * El contador no cambió en toda la medición.
     *
     * Es el fallo del monitor viejo, ahora detectado en vez de publicado como
     * un cero: el bloque fue demasiado corto para la resolución del terminal.
     * No invalida por sí solo si hay corriente que integrar.
     */
    CONTADOR_SIN_VARIACION,

    /** No hay corriente instantánea disponible. No invalida por sí solo. */
    SIN_CORRIENTE,

    /** Menos muestras de las necesarias para que las estadísticas signifiquen algo. */
    MUESTRAS_INSUFICIENTES
}

/**
 * Resultado agregado de un bloque de medición sostenido.
 *
 * `null` en los campos de energía significa "no medible en este terminal o en
 * este bloque", nunca "cero". Confundir ambas cosas fue exactamente el error
 * que invalidó las 676 mediciones anteriores.
 */
data class ResumenRecursos(
    val etiqueta: String,
    val duracionMs: Long,
    val nMuestras: Int,

    /** Carga consumida en µAh según el contador. Positiva al descargar. */
    val consumoMicroAh: Long?,
    /** El anterior extrapolado a una hora. */
    val consumoMicroAhPorHora: Double?,

    /** Consumo en µAh obtenido integrando la corriente instantánea. */
    val consumoIntegradoMicroAh: Double?,
    /** El anterior extrapolado a una hora. */
    val consumoIntegradoMicroAhPorHora: Double?,

    /** Media de la corriente instantánea, en valor absoluto. */
    val corrienteMediaMicroA: Double?,

    /** De dónde sale [tasaConsumoMicroAhPorHora]. */
    val metodoConsumo: MetodoConsumo,

    val pssMinKb: Long,
    val pssMaxKb: Long,
    val pssMedioKb: Double,

    val invalidez: Set<MotivoInvalidez>
) {
    /**
     * La cifra REPORTABLE, en µAh/h, o `null` si no hay ninguna.
     *
     * Se extrapola a una hora porque los bloques rara vez duran lo mismo y una
     * tasa sí se puede comparar entre ellos.
     */
    val tasaConsumoMicroAhPorHora: Double?
        get() = when (metodoConsumo) {
            MetodoConsumo.CONTADOR_DE_CARGA -> consumoMicroAhPorHora
            MetodoConsumo.INTEGRACION_DE_CORRIENTE -> consumoIntegradoMicroAhPorHora
            MetodoConsumo.NINGUNO -> null
        }

    /**
     * Una medición vale si NO se hizo con el cable puesto, tiene muestras
     * suficientes y produjo una cifra de consumo por algún método.
     *
     * Que el contador no resuelva NO la invalida: en un terminal cuyo contador
     * se mueve en escalones del 1%, exigirlo dejaría el estudio sin ninguna
     * medición válida. Los motivos del contador se conservan igualmente en
     * [invalidez] porque hay que poder reportar con qué instrumento se midió.
     */
    val esValida: Boolean
        get() = MotivoInvalidez.CARGANDO !in invalidez &&
            MotivoInvalidez.MUESTRAS_INSUFICIENTES !in invalidez &&
            metodoConsumo != MetodoConsumo.NINGUNO

    val pssMaxMb: Double get() = pssMaxKb / 1024.0
    val pssMedioMb: Double get() = pssMedioKb / 1024.0

    companion object {

        /** Por debajo de esto las estadísticas de memoria no dicen nada. */
        const val MIN_MUESTRAS = 3

        /**
         * Agrega una serie de muestras. FUNCIÓN PURA: sin Android, sin relojes,
         * sin corrutinas. Es el único sitio con lógica y por eso es el único que
         * necesita pruebas unitarias de verdad.
         */
        fun desde(etiqueta: String, muestras: List<MuestraRecursos>): ResumenRecursos {
            require(muestras.isNotEmpty()) { "no se puede resumir una medición sin muestras" }

            val ordenadas = muestras.sortedBy { it.tMs }
            val duracion = ordenadas.last().tMs - ordenadas.first().tMs

            val motivos = mutableSetOf<MotivoInvalidez>()
            if (ordenadas.any { it.cargando }) motivos += MotivoInvalidez.CARGANDO
            if (ordenadas.size < MIN_MUESTRAS) motivos += MotivoInvalidez.MUESTRAS_INSUFICIENTES

            // --- energía por contador de carga ---
            val cargas = ordenadas.mapNotNull { it.cargaMicroAh }
            var consumo: Long? = null
            var consumoPorHora: Double? = null
            var contadorSirve = false
            if (cargas.size < 2) {
                motivos += MotivoInvalidez.SIN_CONTADOR_DE_CARGA
            } else {
                // El contador puede oscilar por ruido de la medición del
                // fabricante, así que se toman los extremos temporales y no el
                // máximo y el mínimo, que sesgarían al alza.
                consumo = cargas.first() - cargas.last()
                if (cargas.toSet().size == 1) {
                    motivos += MotivoInvalidez.CONTADOR_SIN_VARIACION
                } else {
                    contadorSirve = true
                }
                if (duracion > 0) consumoPorHora = consumo * 3_600_000.0 / duracion
            }

            // --- energía por integración de corriente ---
            val corrientes = ordenadas.mapNotNull { it.corrienteMicroA }
            if (corrientes.isEmpty()) motivos += MotivoInvalidez.SIN_CORRIENTE
            // Valor absoluto: el signo de CURRENT_NOW no está normalizado entre
            // fabricantes y aquí sólo interesa la magnitud.
            val corrienteMedia =
                if (corrientes.isEmpty()) null
                else corrientes.map { abs(it).toDouble() }.average()

            val integrado = integrarCorriente(ordenadas)
            val integradoPorHora =
                if (integrado != null && duracion > 0) integrado * 3_600_000.0 / duracion
                else null

            val metodo = when {
                contadorSirve -> MetodoConsumo.CONTADOR_DE_CARGA
                integradoPorHora != null -> MetodoConsumo.INTEGRACION_DE_CORRIENTE
                else -> MetodoConsumo.NINGUNO
            }

            val pss = ordenadas.map { it.pssKb }

            return ResumenRecursos(
                etiqueta = etiqueta,
                duracionMs = duracion,
                nMuestras = ordenadas.size,
                consumoMicroAh = consumo,
                consumoMicroAhPorHora = consumoPorHora,
                consumoIntegradoMicroAh = integrado,
                consumoIntegradoMicroAhPorHora = integradoPorHora,
                corrienteMediaMicroA = corrienteMedia,
                metodoConsumo = metodo,
                pssMinKb = pss.min(),
                pssMaxKb = pss.max(),
                pssMedioKb = pss.map { it.toDouble() }.average(),
                invalidez = motivos
            )
        }

        /**
         * Integra |corriente| en el tiempo por la regla del trapecio, en µAh.
         *
         * POR QUÉ EL TRAPECIO Y NO LA MEDIA POR LA DURACIÓN. Serían lo mismo si
         * las muestras estuvieran perfectamente equiespaciadas, y no lo están:
         * el muestreador usa `delay`, que no garantiza el periodo, y una
         * recolección de basura o una bajada de frecuencia del procesador
         * abren huecos. Con muestras irregulares, promediar da el mismo peso a
         * una lectura que cubre 100 ms que a otra que cubre 2 s, y el trapecio
         * pondera cada una por el intervalo que representa.
         *
         * Devuelve `null` si hay menos de dos lecturas de corriente o si el
         * bloque no dura nada.
         */
        internal fun integrarCorriente(ordenadas: List<MuestraRecursos>): Double? {
            val conCorriente = ordenadas.filter { it.corrienteMicroA != null }
            if (conCorriente.size < 2) return null
            var microAms = 0.0                       // µA * ms
            for ((a, b) in conCorriente.zipWithNext()) {
                val dt = (b.tMs - a.tMs).toDouble()
                if (dt <= 0) continue
                val ia = abs(a.corrienteMicroA!!).toDouble()
                val ib = abs(b.corrienteMicroA!!).toDouble()
                microAms += (ia + ib) / 2.0 * dt
            }
            if (microAms == 0.0) return null
            return microAms / 3_600_000.0            // µA*ms -> µAh
        }

        /**
         * Resta una línea base al consumo, para reportar NETO.
         *
         * El perfil aprobado lo exige: "los valores reportados corresponden al
         * consumo neto, calculado restando el consumo base de la aplicación
         * medido en estado ideal bajo la misma configuración". Se comparan
         * tasas por hora y no totales, porque los dos bloques rara vez duran lo
         * mismo.
         *
         * DEVUELVE `null` SI LOS DOS BLOQUES NO USARON EL MISMO MÉTODO. Restar
         * una tasa obtenida del contador de carga a otra obtenida integrando
         * corriente daría un número con unidades correctas y sin significado:
         * los dos instrumentos no miden lo mismo ni tienen el mismo sesgo.
         */
        fun neto(bloque: ResumenRecursos, lineaBase: ResumenRecursos): Double? {
            if (bloque.metodoConsumo != lineaBase.metodoConsumo) return null
            val a = bloque.tasaConsumoMicroAhPorHora ?: return null
            val b = lineaBase.tasaConsumoMicroAhPorHora ?: return null
            return a - b
        }
    }
}
