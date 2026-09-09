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
import com.example.autenticacioncontinua.data.controlada.SelectorDeConfiguracion
import com.example.autenticacioncontinua.data.controlada.SelectorEnPreferencias
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
import com.example.autenticacioncontinua.data.export.ExportadorDeSesionImpl
import com.example.autenticacioncontinua.domain.export.IExportadorDeSesion

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
 *   1.2  (31/08)  el teclado devuelve un CHASQUIDO por tecla. Sube la version
 *                 de protocolo y no solo la de la app, aunque suene a detalle
 *                 de interfaz, porque cambia el ESTIMULO que recibe el
 *                 participante mientras teclea: con respuesta sonora no hace
 *                 falta mirar la pantalla para confirmar cada pulsacion, y sin
 *                 ella si. Eso altera la conducta que se esta midiendo, que es
 *                 exactamente el criterio de esta columna.
 *
 *                 LA DUDA SE RESUELVE MARCANDO DE MAS, y conviene dejar escrito
 *                 el argumento porque volvera a aparecer: dos versiones
 *                 marcadas se pueden UNIR despues en el analisis si se decide
 *                 que daba igual; dos sesiones distintas con la misma etiqueta
 *                 no se pueden separar nunca. La asimetria es total, asi que
 *                 ante la duda se sube.
 *
 *                 Las sesiones 6, 7 y 9 —los ensayos del 30 y 31/08— son de la
 *                 1.1: se teclearon en silencio.
 *
 *                 1.3 (06/09): LA TASA DE MUESTREO BAJA DE 100 A 50 Hz en los
 *                 tres sensores inerciales. Lo obliga el magnetometro del
 *                 terminal B, que topa en 50 Hz; ver `HZ_CONTROLADO`. Las
 *                 sesiones de la 1.2 y anteriores se capturaron a 100 Hz
 *                 nominales y NO son comparables en tasa por bloque con las de
 *                 la 1.3. La columna de version esta justamente para poder
 *                 separarlas en el analisis.
 */
const val VERSION_PROTOCOLO = "1.3"

/**
 * 50 Hz para los TRES sensores del estudio controlado.
 *
 * BAJADO DE 100 A 50 EL 06/09, Y NO POR SIMPLIFICAR: LO OBLIGA EL HARDWARE.
 * `CapturaSensoresTest` midio ese dia, 60 s por sensor, pidiendo 100 Hz en el
 * Redmi Note 11 Pro (terminal B):
 *
 *     acelerometro   100.72 Hz    intervalo mediano/p95/max  9.93 / 9.93 / 9.93 ms
 *     giroscopio     100.72 Hz    intervalo mediano/p95/max  9.93 / 9.93 / 9.93 ms
 *     magnetometro    50.00 Hz    intervalo mediano/p95/max 20.00 / 20.01 / 20.03 ms
 *
 * El magnetometro TOPA en 50 Hz. No es jitter ni perdida de muestras: son
 * 20.00 ms clavados, con un maximo de 20.03. El aparato entrega 50 Hz y no mas,
 * se le pida lo que se le pida.
 *
 * POR QUE ESO ARRASTRA A LOS OTROS DOS. Porque el estudio es un diseño CRUZADO
 * persona x dispositivo. Si un terminal entregara el magnetometro a 100 Hz y el
 * otro a 50, esa diferencia quedaria METIDA EN EL DATO, y parte del "efecto de
 * aparato" que se quiere medir seria en realidad la tasa de muestreo. Unificar
 * los tres canales a 50 Hz en los dos terminales elimina esa via de confusion
 * de raiz, y ademas deja un corpus con una sola tasa, sin remuestreos cruzados.
 *
 * QUE SE CONSERVA. A 50 Hz el teorema de muestreo deja contenido util hasta
 * 25 Hz, asi que la banda del temblor fisiologico —8 a 20 Hz— sigue DENTRO.
 * Es la que sostiene la discriminacion por motricidad fina al teclear.
 *
 * QUE SE PIERDE, Y HAY QUE DECLARARLO EN LA MEMORIA. La banda de 25 a 50 Hz.
 * La justificacion original hablaba de "8-20 Hz y superior", y el analisis del
 * 18/08 observo que la familia de descriptores `ruido` —bandas por encima de
 * 15 Hz— fue la que mejor generalizo a impostores no vistos (AUC 0.70 y 0.68).
 * A 50 Hz de esa familia solo queda la rendija de 15 a 25 Hz. Es un coste
 * conocido y aceptado, no un descuido.
 *
 * ES UNA SUGERENCIA PARA ANDROID, no una garantia: la tasa efectiva se mide
 * despues con `BloqueDao.tasaEfectivaHz` y hay que comprobarla en cada bloque.
 */
const val HZ_CONTROLADO = 50

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
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13
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

    // Qué configuración de sensores esta activa. `single` porque es estado de
    // protocolo compartido: el medidor de recursos y la captura tienen que ver
    // el mismo valor, y cambiarlo en un sitio tiene que llegar al otro.
    single<SelectorDeConfiguracion> { SelectorEnPreferencias(androidContext()) }

    factory {
        CapturaInercial(
            acelerometro = get(named(TipoSensor.ACELEROMETRO.clave)),
            giroscopio = get(named(TipoSensor.GIROSCOPIO.clave)),
            magnetometro = get(named(TipoSensor.MAGNETOMETRO.clave)),
            repositorio = get(),
            configuracion = get<SelectorDeConfiguracion>()
        )
    }

    // Domain / Session
    single<ISessionManager> { SessionManagerImpl(get(), get(), get(), get(), get(), get()) }

    // Services
    single<IDataExportService> { DataExportServiceImpl(androidContext(), get(), get(), get()) }

    // Paquete por visita (R5, fase 9). Es OTRA cosa que `IDataExportService`,
    // que comprime la base entera: ver la nota de `IExportadorDeSesion`.
    single<IExportadorDeSesion> {
        ExportadorDeSesionImpl(androidContext(), sesiones = get(), participantes = get())
    }

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
            exportador = get(),
            bateria = { get<IBatteryMonitor>().getCurrentLevel().takeIf { it >= 0f } }
        )
    }
}
