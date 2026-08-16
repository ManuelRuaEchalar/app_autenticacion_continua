package com.example.autenticacioncontinua

import android.app.Application
import com.example.autenticacioncontinua.di.appModule
import com.example.autenticacioncontinua.di.mlModule
import com.example.autenticacioncontinua.di.federatedModule
import com.example.autenticacioncontinua.work.ServiceWatchdogWorker
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

        // Red de seguridad del pendiente A2. Va aquí y no en MainActivity a
        // propósito: `Application.onCreate` corre en CUALQUIER arranque del
        // proceso —incluido el que provoca el propio vigía, o un receiver—,
        // mientras que MainActivity sólo si el usuario abre la app. Un
        // participante que instaló el APK y nunca lo abrió es exactamente el
        // caso que dejó dos móviles a cero en agosto.
        ServiceWatchdogWorker.programar(this)
    }
}
