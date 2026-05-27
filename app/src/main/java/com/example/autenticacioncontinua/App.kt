package com.example.autenticacioncontinua

import android.app.Application
import com.example.autenticacioncontinua.di.appModule
import com.example.autenticacioncontinua.di.mlModule
import com.example.autenticacioncontinua.di.federatedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule, mlModule, federatedModule)
        }
    }
}
