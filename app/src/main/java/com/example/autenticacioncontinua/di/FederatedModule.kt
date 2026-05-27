package com.example.autenticacioncontinua.di

import com.example.autenticacioncontinua.federated.FlowerGrpcClient
import com.example.autenticacioncontinua.federated.ModelInfoFetcher
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val federatedModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    single { ModelInfoFetcher(get()) }
    single {
        FlowerGrpcClient(
            context = androidContext(),
            modelManager = get(),
            localTrainer = get(),
            windowSegmenter = get(),
            eerCalculator = get(),
            batteryMonitor = get(),
            ramMonitor = get(),
            metricsRepository = get()
        )
    }
}
