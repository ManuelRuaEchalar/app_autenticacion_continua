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
import com.example.autenticacioncontinua.monitoring.Cronometro
import com.example.autenticacioncontinua.monitoring.FuenteEnergia
import com.example.autenticacioncontinua.monitoring.FuenteEnergiaAndroid
import com.example.autenticacioncontinua.monitoring.FuenteMemoria
import com.example.autenticacioncontinua.monitoring.FuenteMemoriaAndroid
import com.example.autenticacioncontinua.monitoring.MedidorDeOperacion
import com.example.autenticacioncontinua.data.controlada.SelectorDeConfiguracion
import com.example.autenticacioncontinua.monitoring.FuenteEstadoPantalla
import com.example.autenticacioncontinua.monitoring.FuenteEstadoPantallaAndroid
import com.example.autenticacioncontinua.monitoring.MonitorBloque
import com.example.autenticacioncontinua.monitoring.ProtocoloDeBloques
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
            headStore = get(),
            cronometro = get()
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
            thresholdStore = get(),
            cronometro = get()
        )
    }

    // Las fuentes son envoltorios finos sobre la API de Android; los monitores
    // y el MonitorBloque dependen de la INTERFAZ, no del contexto, de modo que
    // su logica se prueba con dobles y sin dispositivo.
    single<FuenteEnergia> { FuenteEnergiaAndroid(androidContext()) }
    single<FuenteMemoria> { FuenteMemoriaAndroid() }
    single<FuenteEstadoPantalla> { FuenteEstadoPantallaAndroid(androidContext()) }

    single<IBatteryMonitor> { BatteryMonitorImpl(get()) }
    single<IRamMonitor> { RamMonitorImpl(get()) }

    // Medicion sobre bloques sostenidos: es la unica forma de medir bateria,
    // porque el contador de carga no resuelve operaciones de pocos segundos.
    single { MonitorBloque(energia = get(), memoria = get(), estadoPantalla = get()) }

    // Latencias: inferencia, entrenamiento local, ronda federada, extremo a
    // extremo. Compartido para que todas las series caigan en el mismo sitio.
    single { Cronometro() }

    // Une bloque + latencia + persistencia detras de una sola llamada. La
    // configuracion de sensores sale del manifiesto y no de una constante: es
    // una variable independiente del diseno y tiene que viajar con cada fila.
    single {
        MedidorDeOperacion(
            monitor = get(),
            cronometro = get(),
            registro = get(),
            // Se consulta en cada medicion, no al construir: ver la nota del
            // parametro en MedidorDeOperacion.
            configSensores = { get<SelectorDeConfiguracion>().activa().clave }
        )
    }

    // Ejecutor del protocolo: bloques largos, linea base por repeticion y
    // orden contrabalanceado. `factory` y no `single` porque cada campana de
    // medicion es independiente y no debe heredar estado de la anterior.
    factory { ProtocoloDeBloques(medidor = get(), energia = get()) }

    single<IResourceMeasurementRepository> { ResourceMeasurementRepositoryImpl(get<AppDatabase>()) }
}
