package com.example.autenticacioncontinua.di

import androidx.room.Room
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.repository.AccelerometerRepositoryImpl
import com.example.autenticacioncontinua.data.repository.GyroscopeRepositoryImpl
import com.example.autenticacioncontinua.data.repository.DeviceEventRepositoryImpl
import com.example.autenticacioncontinua.data.repository.LabeledSessionRepositoryImpl
import com.example.autenticacioncontinua.data.repository.TrainingHistoryRepositoryImpl
import com.example.autenticacioncontinua.device.protection.ProtectionStatus
import com.example.autenticacioncontinua.device.sensor.AccelerometerSensorImpl
import com.example.autenticacioncontinua.device.sensor.GyroscopeSensorImpl
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.repository.ITrainingHistoryRepository
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import com.example.autenticacioncontinua.domain.session.ISessionManager
import com.example.autenticacioncontinua.domain.session.SessionManagerImpl
import com.example.autenticacioncontinua.presentation.FederatedViewModel
import com.example.autenticacioncontinua.presentation.LabeledCaptureViewModel
import com.example.autenticacioncontinua.presentation.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

import com.example.autenticacioncontinua.domain.export.IDataExportService
import com.example.autenticacioncontinua.data.export.DataExportServiceImpl

val appModule = module {
    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "continuous_auth_db"
        ).addMigrations(
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7
        ).build()
    }

    // Repositories
    single<IGyroscopeRepository> { GyroscopeRepositoryImpl(get()) }
    single<IAccelerometerRepository> { AccelerometerRepositoryImpl(get()) }
    single<ITrainingHistoryRepository> { TrainingHistoryRepositoryImpl(get()) }
    single<IDeviceEventRepository> { DeviceEventRepositoryImpl(get()) }
    single<ILabeledSessionRepository> { LabeledSessionRepositoryImpl(get()) }

    // Supervivencia del servicio (pendientes A2 y E)
    single { ProtectionStatus(androidContext()) }

    // Sensors
    single<IGyroscopeSensor> { GyroscopeSensorImpl(androidContext()) }
    single<IAccelerometerSensor> { AccelerometerSensorImpl(androidContext()) }

    // Domain / Session
    single<ISessionManager> { SessionManagerImpl(get(), get(), get(), get(), get(), get()) }

    // Services
    single<IDataExportService> { DataExportServiceImpl(androidContext(), get(), get(), get()) }

    // Presentation
    viewModel { MainViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { FederatedViewModel(get()) }
    viewModel { LabeledCaptureViewModel(get(), get()) }
}
