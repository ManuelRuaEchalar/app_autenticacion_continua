package com.example.autenticacioncontinua.di

import androidx.room.Room
import com.example.autenticacioncontinua.BuildConfig
import com.example.autenticacioncontinua.data.local.AppDatabase
import com.example.autenticacioncontinua.data.repository.AccelerometerRepositoryImpl
import com.example.autenticacioncontinua.data.repository.GyroscopeRepositoryImpl
import com.example.autenticacioncontinua.data.repository.DeviceEventRepositoryImpl
import com.example.autenticacioncontinua.data.repository.LabeledSessionRepositoryImpl
import com.example.autenticacioncontinua.data.repository.ParticipanteRepositoryImpl
import com.example.autenticacioncontinua.data.repository.RegistroMedicionesImpl
import com.example.autenticacioncontinua.data.repository.SesionControladaRepositoryImpl
import com.example.autenticacioncontinua.data.repository.TrainingHistoryRepositoryImpl
import com.example.autenticacioncontinua.device.protection.ProtectionStatus
import com.example.autenticacioncontinua.data.controlada.IdentidadDelDispositivo
import com.example.autenticacioncontinua.data.controlada.IdentidadEnPreferencias
import com.example.autenticacioncontinua.data.sensor.CapturaInercial
import com.example.autenticacioncontinua.data.textos.CorpusDeTextos
import com.example.autenticacioncontinua.device.sensor.AccelerometerSensorImpl
import com.example.autenticacioncontinua.device.sensor.FuenteSensorAndroid
import com.example.autenticacioncontinua.device.sensor.GyroscopeSensorImpl
import com.example.autenticacioncontinua.domain.repository.IAccelerometerRepository
import com.example.autenticacioncontinua.domain.repository.IGyroscopeRepository
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import com.example.autenticacioncontinua.domain.repository.ILabeledSessionRepository
import com.example.autenticacioncontinua.domain.repository.IParticipanteRepository
import com.example.autenticacioncontinua.domain.repository.IRegistroMediciones
import com.example.autenticacioncontinua.domain.repository.ISesionControladaRepository
import com.example.autenticacioncontinua.domain.repository.ITrainingHistoryRepository
import com.example.autenticacioncontinua.domain.sensor.IAccelerometerSensor
import com.example.autenticacioncontinua.domain.sensor.IFuenteSensor
import com.example.autenticacioncontinua.domain.sensor.IGyroscopeSensor
import com.example.autenticacioncontinua.domain.sensor.TipoSensor
import com.example.autenticacioncontinua.domain.session.ISessionManager
import com.example.autenticacioncontinua.domain.session.SessionManagerImpl
import com.example.autenticacioncontinua.monitoring.IBatteryMonitor
import com.example.autenticacioncontinua.presentation.FederatedViewModel
import com.example.autenticacioncontinua.presentation.LabeledCaptureViewModel
import com.example.autenticacioncontinua.presentation.MainViewModel
import com.example.autenticacioncontinua.presentation.controlada.JuegoViewModel
import com.example.autenticacioncontinua.presentation.controlada.ParticipantesViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

import com.example.autenticacioncontinua.domain.export.IDataExportService
import com.example.autenticacioncontinua.data.export.DataExportServiceImpl

/**
 * Version del PROTOCOLO de sesion controlada, independiente de la de la app.
 *
 * Sube cuando cambia algo que afecta a la comparabilidad de los datos: la tasa
 * de muestreo, la duracion del bloque, el numero de bloques, el reparto de
 * idiomas. Un arreglo de interfaz sube la version de la app y NO esta.
 *
 *   1.0  (23/08)  aclimatacion 60 s, tres bloques de 5 min, descansos de 60 s.
 *   1.1  (30/08)  aclimatacion 10 s, tres bloques de 100 s, SIN descansos. La
 *                 visita baja de ~18 min a ~5 min por viabilidad de campo. Se
 *                 conservan los tres bloques, el reparto 2 espanol + 1 latin y
 *                 la rotacion del latin. Ver BloqueEntity.DURACION_MS y
 *                 FaseDeSesion. Las sesiones de la 1.0 y la 1.1 NO son
 *                 comparables en tasas por bloque; la columna esta justamente
 *                 para poder separarlas en el analisis.
 */
const val VERSION_PROTOCOLO = "1.1"

/**
 * 100 Hz para el estudio controlado, frente a los 50 Hz de la recogida
 * ambiental.
 *
 * POR QUE SE SUBE. A 50 Hz el teorema de muestreo deja el contenido util en
 * 25 Hz, y el temblor fisiologico de la mano y el micro-impacto de cada
 * pulsacion viven justo en ese borde. La literatura de dinamica de tecleo con
 * sensores inerciales muestrea habitualmente a 100 Hz por eso. Como el corpus
 * controlado se recoge de cero, no hay nada anterior con lo que tenga que ser
 * compatible.
 *
 * ES UNA SUGERENCIA PARA ANDROID, no una garantia: la tasa efectiva se mide
 * despues con `BloqueDao.tasaEfectivaHz` y hay que comprobarla en cada bloque.
 */
const val HZ_CONTROLADO = 100

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
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11
        ).build()
    }

    // Repositories
    single<IGyroscopeRepository> { GyroscopeRepositoryImpl(get()) }
    single<IAccelerometerRepository> { AccelerometerRepositoryImpl(get()) }
    single<ITrainingHistoryRepository> { TrainingHistoryRepositoryImpl(get()) }
    single<IDeviceEventRepository> { DeviceEventRepositoryImpl(get()) }
    single<ILabeledSessionRepository> { LabeledSessionRepositoryImpl(get()) }
    single<IRegistroMediciones> { RegistroMedicionesImpl(get()) }

    // Corpus controlado. Separado del ambiental en tablas propias: ninguna
    // consulta mezcla `muestras_inerciales` con `accelerometer_data`.
    single<IParticipanteRepository> { ParticipanteRepositoryImpl(get()) }
    single<ISesionControladaRepository> {
        SesionControladaRepositoryImpl(
            db = get(),
            // La version viaja en CADA sesion, no en un fichero de
            // configuracion: la recogida dura 60 dias y la app va a cambiar
            // durante ellos. Sin esta columna, un cambio de tasa de muestreo a
            // mitad del estudio seria indistinguible de un efecto de los datos.
            versionApp = BuildConfig.VERSION_NAME,
            versionProtocolo = VERSION_PROTOCOLO
        )
    }

    // Supervivencia del servicio (pendientes A2 y E)
    single { ProtectionStatus(androidContext()) }

    // Sensores de la recogida AMBIENTAL: adaptadores a 50 Hz sobre la fuente
    // generica. La tasa NO se sube a 100 Hz aqui: partiria el corpus ya
    // recogido en dos regimenes de muestreo distintos.
    single<IGyroscopeSensor> { GyroscopeSensorImpl(androidContext()) }
    single<IAccelerometerSensor> { AccelerometerSensorImpl(androidContext()) }

    // Sensores del estudio CONTROLADO: los tres a 100 Hz, cualificados por
    // tipo. Son instancias APARTE de las ambientales a proposito: compartirlas
    // significaria que iniciar una sesion controlada cambiaria la tasa de la
    // recogida ambiental, o que pararla la dejaria muerta.
    single<IFuenteSensor>(named(TipoSensor.ACELEROMETRO.clave)) {
        FuenteSensorAndroid(androidContext(), TipoSensor.ACELEROMETRO, HZ_CONTROLADO)
    }
    single<IFuenteSensor>(named(TipoSensor.GIROSCOPIO.clave)) {
        FuenteSensorAndroid(androidContext(), TipoSensor.GIROSCOPIO, HZ_CONTROLADO)
    }
    single<IFuenteSensor>(named(TipoSensor.MAGNETOMETRO.clave)) {
        FuenteSensorAndroid(androidContext(), TipoSensor.MAGNETOMETRO, HZ_CONTROLADO)
    }

    factory {
        CapturaInercial(
            acelerometro = get(named(TipoSensor.ACELEROMETRO.clave)),
            giroscopio = get(named(TipoSensor.GIROSCOPIO.clave)),
            magnetometro = get(named(TipoSensor.MAGNETOMETRO.clave)),
            repositorio = get()
        )
    }

    // Domain / Session
    single<ISessionManager> { SessionManagerImpl(get(), get(), get(), get(), get(), get()) }

    // Services
    single<IDataExportService> { DataExportServiceImpl(androidContext(), get(), get(), get()) }

    // Presentation
    viewModel { MainViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { FederatedViewModel(get()) }
    viewModel { LabeledCaptureViewModel(get(), get()) }

    // --- Estudio controlado ---

    // Que terminal es este (A o B). Etiqueta del PROTOCOLO, no del aparato: si
    // uno se rompe, el repuesto hereda la etiqueta o la secuencia alternada de
    // todos los participantes se rompe a mitad del estudio.
    single<IdentidadDelDispositivo> { IdentidadEnPreferencias(androidContext()) }

    // El corpus se carga entero en memoria (~800 KB) para que elegir un parrafo
    // no toque el disco a mitad de un bloque cronometrado.
    single { CorpusDeTextos(androidContext()) }
    single { get<CorpusDeTextos>().selector() }

    viewModel {
        ParticipantesViewModel(
            participantes = get(),
            sesiones = get(),
            // El objeto entero, no la cadena: la lista de verificacion permite
            // ASIGNAR la etiqueta cuando falta, y leerla una vez al construir el
            // ViewModel hacia que el cambio no se viera hasta reiniciar.
            identidad = get(),
            bateria = { get<IBatteryMonitor>().getCurrentLevel().takeIf { it >= 0f } }
        )
    }

    // El minijuego. `ahora` y `bateria` se inyectan como funciones para que la
    // visita entera se pueda simular en la JVM con el reloj virtual de
    // `runTest`: es el mismo patron que MonitorBloque y ProtocoloDeBloques.
    //
    // La bateria es la de SesionControladaEntity, es decir una COVARIABLE de
    // sesion, no la medida de consumo: para eso esta `mediciones_recursos`, que
    // integra la corriente. El porcentaje solo sirve para comprobar que las
    // sesiones no se agruparon en un extremo de la curva de descarga.
    viewModel {
        JuegoViewModel(
            sesiones = get(),
            selector = get(),
            // Una CapturaInercial NUEVA por bloque: la clase guarda el estado de
            // una sola captura y sus contadores de perdidas y descartes se
            // reportan por bloque. `CapturaInercial` ya esta declarada como
            // `factory`, asi que cada `get()` devuelve una distinta.
            capturaDe = { get() },
            ambiental = get(),
            tramos = get(),
            bateria = { get<IBatteryMonitor>().getCurrentLevel().takeIf { it >= 0f } }
        )
    }
}
