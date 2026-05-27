package com.example.autenticacioncontinua.di

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.ml.WindowSegmenter
import com.example.autenticacioncontinua.data.repository.ResourceMeasurementRepositoryImpl
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.repository.IResourceMeasurementRepository
import com.example.autenticacioncontinua.ml.metrics.EERCalculator
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.training.LocalTrainer
import com.example.autenticacioncontinua.monitoring.BatteryMonitorImpl
import com.example.autenticacioncontinua.monitoring.IBatteryMonitor
import com.example.autenticacioncontinua.monitoring.IRamMonitor
import com.example.autenticacioncontinua.monitoring.RamMonitorImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val mlModule = module {
    single { TFLiteModelManager(androidContext()) }
    single { EERCalculator() }
    
    single<IWindowSegmenter> {
        WindowSegmenter(get<IAccelerometerRepository>(), get<IGyroscopeRepository>())
    }
    
    factory { LocalTrainer(get()) }
    
    single<IBatteryMonitor> { BatteryMonitorImpl(androidContext()) }
    single<IRamMonitor> { RamMonitorImpl(androidContext()) }
    
    single<IResourceMeasurementRepository> { ResourceMeasurementRepositoryImpl(get<AppDatabase>()) }
}
