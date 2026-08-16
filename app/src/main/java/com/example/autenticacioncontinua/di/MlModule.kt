package com.example.autenticacioncontinua.di

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.ml.WindowSegmenter
import com.example.autenticacioncontinua.data.repository.ResourceMeasurementRepositoryImpl
import com.example.autenticacioncontinua.domain.ml.IWindowSegmenter
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.repository.IResourceMeasurementRepository
import com.example.autenticacioncontinua.ml.data.BackgroundPool
import com.example.autenticacioncontinua.ml.data.ClientIdentity
import com.example.autenticacioncontinua.ml.data.FeatureScaler
import com.example.autenticacioncontinua.ml.inference.ContinuousAuthenticator
import com.example.autenticacioncontinua.ml.metrics.BinaryMetrics
import com.example.autenticacioncontinua.ml.model.HeadStore
import com.example.autenticacioncontinua.ml.model.ModelManifest
import com.example.autenticacioncontinua.ml.model.TFLiteModelManager
import com.example.autenticacioncontinua.ml.model.ThresholdStore
import com.example.autenticacioncontinua.ml.training.LocalEvaluator
import com.example.autenticacioncontinua.ml.training.LocalTrainer
import com.example.autenticacioncontinua.monitoring.BatteryMonitorImpl
import com.example.autenticacioncontinua.monitoring.IBatteryMonitor
import com.example.autenticacioncontinua.monitoring.IRamMonitor
import com.example.autenticacioncontinua.monitoring.RamMonitorImpl
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Cualificador del pool de impostores usado para entrenar y evaluar. */
const val BACKGROUND_TRAIN = "background_train"

/** Cualificador del pool DISJUNTO usado sólo para calibrar el umbral. */
const val BACKGROUND_CALIB = "background_calib"

/**
 * Grafo de dependencias del subsistema de aprendizaje.
 *
 * Todo cuelga de [ModelManifest], que se lee de los assets al arrancar. Si el
 * `.tflite`, el scaler y los pools de background no proceden de la misma
 * ejecución de `export_tflite_model.py`, la construcción falla aquí y no a
 * mitad de una ronda federada.
 */
val mlModule = module {

    single { ModelManifest.fromAssets(androidContext()) }

    single {
        FeatureScaler.fromAssets(
            context = androidContext(),
            assetName = get<ModelManifest>().scalerStatsFile,
            expectedFeatures = get<ModelManifest>().nFeatures
        )
    }

    single { TFLiteModelManager(androidContext(), get()) }

    single { HeadStore(androidContext(), get<ModelManifest>().headFlatSize) }

    single { ThresholdStore(androidContext(), get<ModelManifest>().decisionThreshold) }

    single { ClientIdentity(androidContext()) }

    single { BinaryMetrics() }

    // Pool de impostores para entrenar y evaluar.
    single(named(BACKGROUND_TRAIN)) {
        val manifest = get<ModelManifest>()
        manifest.backgroundTrainFile?.let {
            BackgroundPool.fromFileOrAssets(
                context = androidContext(),
                assetName = it,
                overrideFileName = "background_peer_train.bin",
                windowFloats = manifest.windowFloats,
                expectedWindows = manifest.backgroundTrainWindows,
                nFeatures = manifest.nFeatures
            )
        } ?: BackgroundPool.empty(manifest.windowFloats)
    }

    // Pool DISJUNTO, sólo para calibrar el umbral. Mantenerlo separado es lo
    // que evita calibrar contra los mismos impostores con los que después se
    // mide el FAR.
    single(named(BACKGROUND_CALIB)) {
        val manifest = get<ModelManifest>()
        manifest.backgroundCalibFile?.let {
            BackgroundPool.fromFileOrAssets(
                context = androidContext(),
                assetName = it,
                overrideFileName = "background_peer_calib.bin",
                windowFloats = manifest.windowFloats,
                expectedWindows = manifest.backgroundCalibWindows,
                nFeatures = manifest.nFeatures
            )
        } ?: BackgroundPool.empty(manifest.windowFloats)
    }

    single<IWindowSegmenter> {
        WindowSegmenter(
            accelerometerRepository = get<IAccelerometerRepository>(),
            gyroscopeRepository = get<IGyroscopeRepository>(),
            labeledSessionRepository = get<ILabeledSessionRepository>(),
            manifest = get(),
            scaler = get()
        )
    }

    factory {
        LocalTrainer(
            modelManager = get(),
            backgroundPool = get(named(BACKGROUND_TRAIN)),
            headStore = get()
        )
    }

    factory {
        LocalEvaluator(
            modelManager = get(),
            backgroundTrainPool = get(named(BACKGROUND_TRAIN)),
            backgroundCalibPool = get(named(BACKGROUND_CALIB)),
            headStore = get(),
            thresholdStore = get(),
            metrics = get()
        )
    }

    factory {
        ContinuousAuthenticator(
            modelManager = get(),
            windowSegmenter = get(),
            headStore = get(),
            thresholdStore = get()
        )
    }

    single<IBatteryMonitor> { BatteryMonitorImpl(androidContext()) }
    single<IRamMonitor> { RamMonitorImpl(androidContext()) }

    single<IResourceMeasurementRepository> { ResourceMeasurementRepositoryImpl(get<AppDatabase>()) }
}
