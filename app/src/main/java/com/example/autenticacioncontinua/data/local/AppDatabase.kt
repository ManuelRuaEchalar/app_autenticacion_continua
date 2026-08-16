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
import com.example.autenticacioncontinua.data.local.dao.ResourceMeasurementDao
import com.example.autenticacioncontinua.data.local.dao.TrainingRunDao
import com.example.autenticacioncontinua.data.local.entity.DeviceEventEntity
import com.example.autenticacioncontinua.data.local.entity.LabeledSessionEntity
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
        LabeledSessionEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gyroscopeDao(): GyroscopeDao
    abstract fun accelerometerDao(): AccelerometerDao
    abstract fun sessionStatsDao(): SessionStatsDao
    abstract fun resourceMeasurementDao(): ResourceMeasurementDao
    abstract fun trainingRunDao(): TrainingRunDao
    abstract fun deviceEventDao(): DeviceEventDao
    abstract fun labeledSessionDao(): LabeledSessionDao

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
    }
}
