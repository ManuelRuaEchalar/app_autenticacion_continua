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
import com.example.autenticacioncontinua.data.local.entity.ResourceMeasurementEntity

@Database(
    entities = [
        GyroscopeEntity::class,
        AccelerometerEntity::class,
        DailySessionStatEntity::class,
        ResourceMeasurementEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gyroscopeDao(): GyroscopeDao
    abstract fun accelerometerDao(): AccelerometerDao
    abstract fun sessionStatsDao(): SessionStatsDao
    abstract fun resourceMeasurementDao(): ResourceMeasurementDao

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
    }
}
