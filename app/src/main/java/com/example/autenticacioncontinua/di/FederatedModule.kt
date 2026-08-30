package com.example.autenticacioncontinua.di

import com.example.autenticacioncontinua.federated.FlowerGrpcClient
import com.example.autenticacioncontinua.federated.ModelInfoFetcher
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val federatedModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    single { ModelInfoFetcher(get()) }
    single {
        FlowerGrpcClient(
            context = androidContext(),
            modelManager = get(),
            localTrainer = get(),
            localEvaluator = get(),
            windowSegmenter = get(),
            clientIdentity = get(),
            batteryMonitor = get(),
            ramMonitor = get(),
            metricsRepository = get(),
            medidor = get()
        )
    }
}
