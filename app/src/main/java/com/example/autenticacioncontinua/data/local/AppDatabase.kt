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
import com.example.autenticacioncontinua.data.local.dao.ResourceMeasurementDao
import com.example.autenticacioncontinua.data.local.dao.TrainingRunDao
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity
import com.example.autenticacioncontinua.data.local.entity.TrainingRunEntity

@Database(
    entities = [
        GyroscopeEntity::class,
        AccelerometerEntity::class,
        DailySessionStatEntity::class,
        ResourceMeasurementEntity::class,
        TrainingRunEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gyroscopeDao(): GyroscopeDao
    abstract fun accelerometerDao(): AccelerometerDao
    abstract fun sessionStatsDao(): SessionStatsDao
    abstract fun resourceMeasurementDao(): ResourceMeasurementDao
    abstract fun trainingRunDao(): TrainingRunDao

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
    }
}
