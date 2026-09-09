package com.example.autenticacioncontinua.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.autenticacioncontinua.data.local.dao.AccelerometerDao
import com.example.autenticacioncontinua.data.local.dao.GyroscopeDao
import com.example.autenticacioncontinua.data.local.dao.SessionStatsDao
import com.example.autenticacioncontinua.data.local.entity.AccelerometerEntity
import com.example.autenticacioncontinua.data.local.entity.DailySessionStatEntity
import com.example.autenticacioncontinua.data.local.entity.GyroscopeEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.autenticacioncontinua.data.local.dao.DeviceEventDao
import com.example.autenticacioncontinua.data.local.dao.LabeledSessionDao
import com.example.autenticacioncontinua.data.local.dao.MedicionLatenciaDao
import com.example.autenticacioncontinua.data.local.dao.controlada.BloqueDao
import com.example.autenticacioncontinua.data.local.dao.controlada.ParticipanteDao
import com.example.autenticacioncontinua.data.local.dao.controlada.SesionControladaDao
import com.example.autenticacioncontinua.data.local.dao.MedicionRecursosDao
import com.example.autenticacioncontinua.data.local.dao.ResourceMeasurementDao
import com.example.autenticacioncontinua.data.local.dao.TrainingRunDao
import com.example.autenticacioncontinua.data.local.entity.DeviceEventEntity
import com.example.autenticacioncontinua.data.local.entity.LabeledSessionEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionLatenciaEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.BloqueEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.CovariableSesionEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.EventoTecleoEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.MuestraInercialEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.ParticipanteEntity
import com.example.autenticacioncontinua.data.local.entity.controlada.SesionControladaEntity
import com.example.autenticacioncontinua.data.local.entity.MedicionRecursosEntity
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity
import com.example.autenticacioncontinua.data.local.entity.TrainingRunEntity

@Database(
    entities = [
        GyroscopeEntity::class,
        AccelerometerEntity::class,
        DailySessionStatEntity::class,
        ResourceMeasurementEntity::class,
        TrainingRunEntity::class,
        DeviceEventEntity::class,
        LabeledSessionEntity::class,
        MedicionRecursosEntity::class,
        MedicionLatenciaEntity::class,
        // Corpus controlado. Va en las MISMAS tablas de esta base pero en
        // tablas propias: la separacion de corpus que exige el diseno es de
        // TABLA, no de fichero, y ninguna consulta del analisis mezcla
        // `muestras_inerciales` con `accelerometer_data`.
        ParticipanteEntity::class,
        SesionControladaEntity::class,
        BloqueEntity::class,
        MuestraInercialEntity::class,
        EventoTecleoEntity::class,
        CovariableSesionEntity::class
    ],
    version = 13,
    // Se exporta a `app/schemas`. Es lo que permite contrastar el SQL de las
    // migraciones con el esquema real sin un telefono delante.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gyroscopeDao(): GyroscopeDao
    abstract fun accelerometerDao(): AccelerometerDao
    abstract fun sessionStatsDao(): SessionStatsDao
    abstract fun resourceMeasurementDao(): ResourceMeasurementDao
    abstract fun trainingRunDao(): TrainingRunDao
    abstract fun deviceEventDao(): DeviceEventDao
    abstract fun labeledSessionDao(): LabeledSessionDao
    abstract fun medicionRecursosDao(): MedicionRecursosDao
    abstract fun medicionLatenciaDao(): MedicionLatenciaDao
    abstract fun participanteDao(): ParticipanteDao
    abstract fun sesionControladaDao(): SesionControladaDao
    abstract fun bloqueDao(): BloqueDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resource_measurements` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`tag` TEXT NOT NULL, " +
                            "`operationType` TEXT NOT NULL, " +
                            "`sensorConfig` TEXT NOT NULL, " +
                            "`batteryDeltaPercent` REAL NOT NULL, " +
                            "`ramPeakMb` REAL NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, " +
                            "`eerValue` REAL NOT NULL, " +
                            "`timestampMs` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Historial de sesiones federadas.
         *
         * Se migra en vez de recrear la base porque las tablas de sensores
         * contienen los datos de recolección del usuario, que son
         * irrecuperables: `fallbackToDestructiveMigration` aquí significaría
         * perder días de trabajo de campo.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `training_runs` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`startedAtMs` INTEGER NOT NULL, " +
                            "`finishedAtMs` INTEGER NOT NULL, " +
                            "`rounds` INTEGER NOT NULL, " +
                            "`trainWindows` INTEGER NOT NULL, " +
                            "`valWindows` INTEGER NOT NULL, " +
                            "`testWindows` INTEGER NOT NULL, " +
                            "`sessionCount` INTEGER NOT NULL, " +
                            "`lastValAuc` REAL NOT NULL, " +
                            "`lastValEer` REAL NOT NULL, " +
                            "`testAuc` REAL NOT NULL, " +
                            "`testEer` REAL NOT NULL, " +
                            "`testFar` REAL NOT NULL, " +
                            "`testFrr` REAL NOT NULL, " +
                            "`threshold` REAL NOT NULL, " +
                            "`completed` INTEGER NOT NULL, " +
                            "`errorMessage` TEXT)"
                )
            }
        }

        /**
         * Diario de a bordo del dispositivo (pendiente A2).
         *
         * Migración, no recreación, por lo mismo que la 4->5: en esa base
         * están los días de recolección de campo de los participantes, y
         * volver a pedírselos no es una opción.
         *
         * El índice por `timestampMs` no es adorno: todas las consultas del
         * diario ordenan o filtran por tiempo (`getRecent`, `countSince`,
         * `deleteOlderThan`), y la tabla convive con las de sensores en la
         * misma base.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_events` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestampMs` INTEGER NOT NULL, " +
                            "`type` TEXT NOT NULL, " +
                            "`detail` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_device_events_timestampMs` " +
                            "ON `device_events` (`timestampMs`)"
                )
            }
        }

        /**
         * Ráfagas etiquetadas por participante (captura de impostor en el
         * mismo dispositivo).
         *
         * Tabla nueva y nada más: NO se toca `accelerometer_data` ni
         * `gyroscope_data`. Son las dos tablas de un millón de filas, y añadir
         * una columna a cualquiera de ellas obliga a SQLite a reescribirla
         * entera durante el arranque de la app, en el teléfono del
         * participante y sobre los únicos datos de campo que existen. Un
         * intervalo `[startMs, endMs]` da la misma información por una fila
         * cada tres minutos.
         *
         * `isOwner` va como INTEGER porque SQLite no tiene booleano; Room lo
         * mapea a Boolean. El índice debe coincidir EXACTAMENTE con el
         * declarado en `LabeledSessionEntity` o Room aborta el arranque al
         * validar el esquema (es lo que ya documenta la 5->6).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `labeled_sessions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`participantId` TEXT NOT NULL, " +
                            "`startMs` INTEGER NOT NULL, " +
                            "`endMs` INTEGER NOT NULL, " +
                            "`isOwner` INTEGER NOT NULL, " +
                            "`note` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labeled_sessions_startMs` " +
                            "ON `labeled_sessions` (`startMs`)"
                )
            }
        }

        /**
         * El SQL de la 7->8, como DATOS y no enterrado en el cuerpo de la
         * migracion.
         *
         * Asi una prueba de la JVM puede contrastarlo, sentencia a sentencia,
         * con el esquema que Room exporta a `app/schemas`. Una discrepancia
         * —un tipo, un NOT NULL, el nombre de un indice— no falla al compilar:
         * falla al ARRANCAR la app, en el telefono del participante, sobre los
         * unicos datos de campo que existen.
         */
        internal val SQL_7_8: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `mediciones_recursos` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`etiqueta` TEXT NOT NULL, " +
                            "`tipoOperacion` TEXT NOT NULL, " +
                            "`configSensores` TEXT NOT NULL, " +
                            "`regimenAprendizaje` TEXT NOT NULL, " +
                            "`duracionMs` INTEGER NOT NULL, " +
                            "`nMuestras` INTEGER NOT NULL, " +
                            "`consumoMicroAh` INTEGER, " +
                            "`consumoMicroAhPorHora` REAL, " +
                            "`corrienteMediaMicroA` REAL, " +
                            "`pssMinKb` INTEGER NOT NULL, " +
                            "`pssMaxKb` INTEGER NOT NULL, " +
                            "`pssMedioKb` REAL NOT NULL, " +
                            "`invalidez` TEXT NOT NULL, " +
                            "`tMs` INTEGER NOT NULL)",

            "CREATE INDEX IF NOT EXISTS `index_mediciones_recursos_tMs` " +
                            "ON `mediciones_recursos` (`tMs`)",

            "CREATE INDEX IF NOT EXISTS " +
                            "`index_mediciones_recursos_configSensores` " +
                            "ON `mediciones_recursos` (`configSensores`)",

            "CREATE TABLE IF NOT EXISTS `mediciones_latencia` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`etiqueta` TEXT NOT NULL, " +
                            "`configSensores` TEXT NOT NULL, " +
                            "`regimenAprendizaje` TEXT NOT NULL, " +
                            "`n` INTEGER NOT NULL, " +
                            "`mediaMs` REAL NOT NULL, " +
                            "`medianaMs` REAL NOT NULL, " +
                            "`p95Ms` REAL NOT NULL, " +
                            "`minMs` REAL NOT NULL, " +
                            "`maxMs` REAL NOT NULL, " +
                            "`tMs` INTEGER NOT NULL)",

            "CREATE INDEX IF NOT EXISTS `index_mediciones_latencia_tMs` " +
                            "ON `mediciones_latencia` (`tMs`)",

            "CREATE INDEX IF NOT EXISTS " +
                            "`index_mediciones_latencia_configSensores` " +
                            "ON `mediciones_latencia` (`configSensores`)"
        )

        /**
         * Columnas nuevas en `mediciones_recursos`, por lo que midio el Redmi.
         *
         * POR QUE HAY UNA 9->10 EN VEZ DE HABER CORREGIDO LA 8->9. Porque la
         * 8->9 ya se aplico en el terminal del estudio el 24/08 y las
         * migraciones no se reescriben: una base que ya avanzo no puede
         * "des-avanzar", y un terminal migrado con la version vieja y otro con
         * la nueva darian esquemas distintos con el mismo numero de version.
         *
         * ALTER TABLE ADD COLUMN es barato AQUI y no en general: `ALTER` en
         * SQLite reescribe la tabla entera, pero `ADD COLUMN` no —solo toca la
         * cabecera— y ademas esta tabla tiene cero filas en todos los
         * terminales, porque se creo hace unos minutos. Sobre
         * `accelerometer_data` esto seguiria estando prohibido.
         *
         * QUE ANADEN. El 24/08 se midio que el contador de carga del Redmi
         * 23129RA5FL se mueve en escalones de 49 370 uAh (el 1% de su bateria):
         * no resuelve un bloque de minutos. La corriente instantanea si, asi
         * que ahora cada medicion lleva TAMBIEN el consumo integrado, el METODO
         * con el que se obtuvo la cifra reportable —dos bloques medidos con
         * instrumentos distintos no son comparables— y un booleano `valida`,
         * porque la regla de validez dejo de ser "sin motivos de invalidez":
         * que el contador no resuelva se anota, pero no invalida.
         *
         * Los valores por defecto son los de una fila que no midio nada. No
         * hay filas que rellenar, pero SQLite exige un DEFAULT para anadir una
         * columna NOT NULL.
         */
        internal val SQL_9_10: List<String> = listOf(
            "ALTER TABLE `mediciones_recursos` ADD COLUMN `consumoIntegradoMicroAh` REAL",

            "ALTER TABLE `mediciones_recursos` " +
                "ADD COLUMN `consumoIntegradoMicroAhPorHora` REAL",

            "ALTER TABLE `mediciones_recursos` " +
                "ADD COLUMN `metodoConsumo` TEXT NOT NULL DEFAULT 'NINGUNO'",

            "ALTER TABLE `mediciones_recursos` " +
                "ADD COLUMN `tasaConsumoMicroAhPorHora` REAL",

            "ALTER TABLE `mediciones_recursos` " +
                "ADD COLUMN `valida` INTEGER NOT NULL DEFAULT 0"
        )

        /**
         * 10 -> 11: se ELIMINAN las covariables de `participantes`.
         *
         * Desaparecen `tramoEdad`, `sexo`, `lateralidad`, `competenciaLatin` y
         * `notas`. Quedan sólo `id`, `seudonimo` y `fechaAltaMs`. La razón está
         * en la nota de [ParticipanteEntity]: con 20-30 participantes esos
         * campos juntos reidentifican a casi cualquiera, y la única forma de que
         * un dato no se filtre es que no exista.
         *
         * ES LA PRIMERA MIGRACIÓN NO ADITIVA DEL PROYECTO, y por eso hay que
         * justificar por qué se permite aquí lo que el resto tiene prohibido.
         * SQLite no soporta `DROP COLUMN` antes de la 3.35 —el terminal del
         * estudio va con Android 13, es decir SQLite 3.32— así que la única vía
         * es recrear la tabla y copiar. Eso reescribe la tabla entera, que es
         * exactamente lo que `EsquemaDeMigracionTest` prohíbe... para las tablas
         * de campo. `participantes` tiene una fila por persona del estudio:
         * decenas, no millones. La prohibición sigue en pie donde importa, y la
         * prueba la mantiene comprobando que esta excepción no toca ninguna otra
         * tabla.
         *
         * LAS CLAVES AJENAS SON LA PRECONDICIÓN, Y ESTÁ MEDIDA.
         * `sesiones_controladas.participanteId` apunta a `participantes(id)` con
         * borrado en cascada, y `DROP TABLE` hace un DELETE implícito de todas
         * las filas antes de borrar la tabla: con las claves ajenas ACTIVAS, esa
         * cascada se lleva por delante las sesiones, los bloques y los eventos de
         * tecleo. No es una hipótesis — `EsquemaDeMigracionTest` lo comprueba en
         * los dos sentidos, y la prueba que lo demuestra se llama
         * `con las claves ajenas activas la recreacion arrastraria las sesiones`.
         *
         * Funciona porque Room desactiva `foreign_keys` mientras corre las
         * migraciones y las reactiva al abrir; los `id` se copian tal cual, de
         * modo que al terminar todas las referencias siguen siendo válidas y
         * `PRAGMA foreign_key_check` sale limpio.
         *
         * POR ESO ESTA MIGRACIÓN SE APLICA ANTES DE EMPEZAR EL CAMPO, con
         * `participantes` prácticamente vacía. Un cambio así con veinte
         * participantes y sus diez visitas dentro exige exportar antes.
         *
         * El índice único de `seudonimo` se recrea explícitamente: se fue con la
         * tabla vieja, y sin él dos altas del mismo participante volverían a
         * poder partirlo en dos identidades.
         */
        /**
         * `estadoPantalla` en las dos tablas de medicion.
         *
         * POR QUE HACE FALTA. Ejecutar trabajo con otra aplicacion en primer
         * plano sale mas barato que con el telefono en reposo: la pantalla ya
         * esta encendida, el procesador despierto y la radio activa, de modo
         * que a nuestro trabajo solo se le atribuye el incremento. Sin esta
         * columna, la diferencia entre la configuracion A y la B podria ser en
         * realidad la diferencia entre haberlas medido con la pantalla
         * encendida y con la pantalla apagada, que es de otro orden de
         * magnitud. Con ella, `ResumenRecursos.neto` puede negarse a restar una
         * linea base tomada en otro regimen, igual que ya se niega a restar
         * entre metodos de medida distintos.
         *
         * ADD COLUMN sigue siendo barato aqui por la misma razon que en la
         * 9->10: solo toca la cabecera y las dos tablas tienen cero filas en
         * todos los terminales, porque la campana de medicion aun no se ha
         * ejecutado. Sobre `accelerometer_data` seguiria estando prohibido.
         *
         * EL DEFECTO ES 'DESCONOCIDO' Y NO UN ESTADO CONCRETO. Si alguna fila
         * previa existiera, su regimen no se observo y decir que fue
         * PRIMER_PLANO seria inventarselo; DESCONOCIDO la deja fuera de los
         * analisis por estado, que es lo correcto.
         */
        /**
         * 12 -> 13: indice por `timestamp` en las dos tablas de sensores.
         *
         * POR QUE. Esta migracion no la pide una funcionalidad nueva: la pide un
         * fallo medido. La noche del 06/09, con 1 989 457 filas de acelerometro
         * y 1 942 451 de giroscopio, la sesion federada murio con
         * `OutOfMemoryError` en `GyroscopeEntityKt.toDomain` tras seis minutos
         * dentro de `prepareDataset`. La causa tenia dos mitades y esta es la
         * primera: sin indice, `WHERE timestamp >= ? ORDER BY timestamp` no
         * puede recorrerse en orden, asi que SQLite leia la tabla entera y la
         * ordenaba en un b-tree temporal, y el resultado —1,4 millones de filas
         * por sensor— solo podia entregarse de una vez. La otra mitad, leer por
         * bloques en vez de materializar la lista, esta en `SerieTriaxial`; sin
         * este indice esa lectura por bloques seria cuadratica y no serviria de
         * nada.
         *
         * CREATE INDEX SI ES CARO AQUI, al contrario que los ADD COLUMN de las
         * migraciones anteriores: hay que ordenar dos millones de filas por
         * tabla, y en el terminal de campo eso son decenas de segundos con la
         * base bloqueada. Se paga UNA vez, en el primer arranque tras
         * actualizar, y a cambio cada ventaneo posterior deja de pagarlo.
         *
         * EL NOMBRE NO ES LIBRE. Room valida el esquema al abrir comparandolo
         * con el que genera de las anotaciones, y espera exactamente
         * `index_<tabla>_<columna>`. Un nombre distinto aqui haria fallar la
         * apertura en el terminal ya migrado, que es el peor sitio posible para
         * descubrirlo.
         */
        internal val SQL_12_13: List<String> = listOf(
            "CREATE INDEX IF NOT EXISTS `index_accelerometer_data_timestamp` " +
                "ON `accelerometer_data` (`timestamp`)",

            "CREATE INDEX IF NOT EXISTS `index_gyroscope_data_timestamp` " +
                "ON `gyroscope_data` (`timestamp`)"
        )

        internal val SQL_11_12: List<String> = listOf(
            "ALTER TABLE `mediciones_recursos` " +
                "ADD COLUMN `estadoPantalla` TEXT NOT NULL DEFAULT 'DESCONOCIDO'",

            "ALTER TABLE `mediciones_latencia` " +
                "ADD COLUMN `estadoPantalla` TEXT NOT NULL DEFAULT 'DESCONOCIDO'"
        )

        internal val SQL_10_11: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `participantes_nueva` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`seudonimo` TEXT NOT NULL, " +
                "`fechaAltaMs` INTEGER NOT NULL)",

            "INSERT INTO `participantes_nueva` (`id`, `seudonimo`, `fechaAltaMs`) " +
                "SELECT `id`, `seudonimo`, `fechaAltaMs` FROM `participantes`",

            "DROP TABLE `participantes`",

            "ALTER TABLE `participantes_nueva` RENAME TO `participantes`",

            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_participantes_seudonimo` ON `participantes` (`seudonimo`)"
        )

        /** Ver la nota de [SQL_7_8]. */
        internal val SQL_8_9: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `participantes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`seudonimo` TEXT NOT NULL, " +
                            "`fechaAltaMs` INTEGER NOT NULL, " +
                            "`tramoEdad` TEXT NOT NULL, " +
                            "`sexo` TEXT NOT NULL, " +
                            "`lateralidad` TEXT NOT NULL, " +
                            "`competenciaLatin` TEXT NOT NULL, " +
                            "`notas` TEXT NOT NULL)",

            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_participantes_seudonimo` ON `participantes` (`seudonimo`)",

            "CREATE TABLE IF NOT EXISTS `sesiones_controladas` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`participanteId` INTEGER NOT NULL, " +
                            "`dispositivoId` TEXT NOT NULL, " +
                            "`inicioMs` INTEGER NOT NULL, " +
                            "`finMs` INTEGER NOT NULL, " +
                            "`ordenDispositivo` INTEGER NOT NULL, " +
                            "`semillaSeleccion` INTEGER NOT NULL, " +
                            "`versionApp` TEXT NOT NULL, " +
                            "`versionProtocolo` TEXT NOT NULL, " +
                            "`bateriaInicio` REAL, " +
                            "`bateriaFin` REAL, " +
                            "`estado` TEXT NOT NULL, " +
                            "`motivoInvalidacion` TEXT NOT NULL, " +
                            "FOREIGN KEY(`participanteId`) REFERENCES `participantes`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",

            "CREATE INDEX IF NOT EXISTS " +
                            "`index_sesiones_controladas_participanteId` " +
                            "ON `sesiones_controladas` (`participanteId`)",

            "CREATE INDEX IF NOT EXISTS `index_sesiones_controladas_inicioMs` " +
                            "ON `sesiones_controladas` (`inicioMs`)",

            "CREATE TABLE IF NOT EXISTS `bloques` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sesionId` INTEGER NOT NULL, " +
                            "`indice` INTEGER NOT NULL, " +
                            "`inicioMs` INTEGER NOT NULL, " +
                            "`finMs` INTEGER NOT NULL, " +
                            "`idioma` TEXT NOT NULL, " +
                            "`parrafosUsados` TEXT NOT NULL, " +
                            "`pulsaciones` INTEGER NOT NULL, " +
                            "`errores` INTEGER NOT NULL, " +
                            "`borrados` INTEGER NOT NULL, " +
                            "`ppm` REAL NOT NULL, " +
                            "`precision` REAL NOT NULL, " +
                            "`interrumpido` INTEGER NOT NULL, " +
                            "`motivoInterrupcion` TEXT NOT NULL, " +
                            "FOREIGN KEY(`sesionId`) REFERENCES `sesiones_controladas`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",

            "CREATE INDEX IF NOT EXISTS `index_bloques_sesionId` " +
                            "ON `bloques` (`sesionId`)",

            "CREATE TABLE IF NOT EXISTS `muestras_inerciales` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`bloqueId` INTEGER NOT NULL, " +
                            "`tParedMs` INTEGER NOT NULL, " +
                            "`tMonotonoNs` INTEGER NOT NULL, " +
                            "`accX` REAL NOT NULL, " +
                            "`accY` REAL NOT NULL, " +
                            "`accZ` REAL NOT NULL, " +
                            "`gyrX` REAL NOT NULL, " +
                            "`gyrY` REAL NOT NULL, " +
                            "`gyrZ` REAL NOT NULL, " +
                            "`magX` REAL, " +
                            "`magY` REAL, " +
                            "`magZ` REAL, " +
                            "FOREIGN KEY(`bloqueId`) REFERENCES `bloques`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",

            "CREATE INDEX IF NOT EXISTS " +
                            "`index_muestras_inerciales_bloqueId_tMonotonoNs` " +
                            "ON `muestras_inerciales` (`bloqueId`, `tMonotonoNs`)",

            "CREATE TABLE IF NOT EXISTS `eventos_tecleo` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`bloqueId` INTEGER NOT NULL, " +
                            "`parrafoId` TEXT NOT NULL, " +
                            "`posicion` INTEGER NOT NULL, " +
                            "`esperado` TEXT NOT NULL, " +
                            "`recibido` TEXT NOT NULL, " +
                            "`acierto` INTEGER NOT NULL, " +
                            "`borrado` INTEGER NOT NULL, " +
                            "`tDownMs` INTEGER NOT NULL, " +
                            "`tUpMs` INTEGER NOT NULL, " +
                            "`x` REAL, " +
                            "`y` REAL, " +
                            "`presion` REAL, " +
                            "`area` REAL, " +
                            "FOREIGN KEY(`bloqueId`) REFERENCES `bloques`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",

            "CREATE INDEX IF NOT EXISTS `index_eventos_tecleo_bloqueId_tDownMs` " +
                            "ON `eventos_tecleo` (`bloqueId`, `tDownMs`)",

            "CREATE TABLE IF NOT EXISTS `covariables_sesion` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sesionId` INTEGER NOT NULL, " +
                            "`tMs` INTEGER NOT NULL, " +
                            "`luz` REAL, " +
                            "`proximidad` REAL, " +
                            "`tempBateria` REAL, " +
                            "`bateria` REAL, " +
                            "FOREIGN KEY(`sesionId`) REFERENCES `sesiones_controladas`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",

            "CREATE INDEX IF NOT EXISTS `index_covariables_sesion_sesionId_tMs` " +
                            "ON `covariables_sesion` (`sesionId`, `tMs`)"
        )

        /**
         * Medicion de recursos con instrumentos que si resuelven.
         *
         * POR QUE DOS TABLAS NUEVAS Y NO TOCAR `resource_measurements`. Esa
         * tabla guarda `batteryDeltaPercent`, un delta de porcentaje entero:
         * en las bases de campo del 17/08 valio exactamente 0.0 en 669 de sus
         * 676 filas, porque el 1% de una bateria de 5 000 mAh son 50 000 uAh y
         * una inferencia no gasta eso. Sus filas se conservan como registro de
         * lo que se hizo, pero no se mezclan con las nuevas: en la misma tabla
         * invitarian a promediarse juntas.
         *
         * Aqui tampoco se toca ninguna tabla existente. Solo CREATE TABLE y
         * CREATE INDEX, que en SQLite no reescriben nada de lo que ya hay: el
         * arranque de la app en el telefono del participante no puede quedarse
         * reescribiendo `accelerometer_data`.
         *
         * Los tres campos de energia son NULLable a proposito. Un terminal que
         * no expone BATTERY_PROPERTY_CHARGE_COUNTER, o un bloque demasiado
         * corto para la resolucion del contador, tienen que dar NULL y no cero
         * — distinguir "no se pudo medir" de "consumio cero" es justo lo que
         * fallaba antes. Los nombres de indice siguen el convenio de Room
         * (`index_<tabla>_<columna>`): si no coinciden EXACTAMENTE con los
         * declarados en la entidad, Room aborta el arranque al validar.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_7_8.forEach(db::execSQL)
            }
        }

        /**
         * Corpus del estudio controlado: participantes, sesiones, bloques,
         * muestras inerciales, eventos de tecleo y covariables.
         *
         * SEIS TABLAS NUEVAS Y CERO CAMBIOS EN LAS VIEJAS. La recoleccion
         * ambiental por rafagas sigue funcionando exactamente igual: no se
         * anade ni una columna a `accelerometer_data`, `gyroscope_data` ni
         * `labeled_sessions`. Es la restriccion que fijo el usuario el 23/08 y
         * ademas la unica forma de que el arranque de la app no se quede
         * reescribiendo dos tablas de un millon de filas.
         *
         * POR QUE ESTAN EN LA MISMA BASE Y NO EN OTRA. La separacion de corpus
         * que exige el diseno es de TABLA, no de fichero: ninguna consulta
         * mezcla `muestras_inerciales` con `accelerometer_data`, y ninguna
         * tuberia de analisis lee de las dos a la vez. Una segunda base
         * obligaria a un segundo `RoomDatabase`, un segundo juego de
         * migraciones y una exportacion doble, sin ganar ninguna garantia que
         * no de ya el separar las tablas.
         *
         * CLAVES AJENAS CON BORRADO EN CASCADA. Borrar un participante tiene
         * que llevarse sus sesiones, bloques, muestras y eventos: si no,
         * quedarian veintitantos millones de filas huerfanas que ninguna
         * consulta encontraria y que seguirian ocupando el disco del telefono.
         * Room activa `PRAGMA foreign_keys` por su cuenta, asi que la cascada
         * la aplica SQLite y no codigo nuestro.
         *
         * EL UNICO INDICE UNICO ES EL DEL SEUDONIMO. Dos altas del mismo
         * participante lo partirian en dos personas distintas, y en un analisis
         * con particion disjunta por persona eso es fuga de identidad entre
         * entrenamiento y prueba: la misma persona a los dos lados.
         *
         * Los nombres de indice siguen el convenio de Room
         * (`index_<tabla>_<columnas>`) y las clausulas de clave ajena llevan
         * `ON UPDATE NO ACTION ON DELETE CASCADE` porque es lo que Room genera;
         * cualquier diferencia y aborta el arranque al validar el esquema.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_8_9.forEach(db::execSQL)
            }
        }

        /** Ver la nota de [SQL_9_10]. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_9_10.forEach(db::execSQL)
            }
        }

        /** Ver la nota de [SQL_10_11]. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_10_11.forEach(db::execSQL)
            }
        }

        /** Ver la nota de [SQL_11_12]. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_11_12.forEach(db::execSQL)
            }
        }

        /** Ver la nota de [SQL_12_13]. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SQL_12_13.forEach(db::execSQL)
            }
        }
    }
}
