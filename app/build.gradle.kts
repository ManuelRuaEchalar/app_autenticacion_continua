plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.example.autenticacioncontinua"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.autenticacioncontinua"
        minSdk = 24
        targetSdk = 36
        // Subir SIEMPRE al repartir un APK nuevo. Con 3 participantes y
        // actualizaciones por WhatsApp, sin esto no hay forma de saber quien
        // lleva que compilacion, y un fallo de recoleccion se confunde con un
        // participante que no actualizo.
        //   2 / 1.1  (2026-08-12)  arreglo de la recoleccion por rafagas:
        //                          una rafaga por proceso, enfriamiento
        //                          congelado, COOLDOWN y DAILY_LIMIT sin
        //                          salida. Ver PENDIENTES.txt.
        //   3 / 1.2  (2026-08-15)  pendiente B2: el umbral se calibra con
        //                          validacion, no con train. Antes salia
        //                          far=0.0 frr=1.0, o sea un sistema que
        //                          rechazaba al usuario legitimo siempre.
        //   4 / 1.3  (2026-08-15)  guarda de reentrada en
        //                          FederatedLearningService: un segundo
        //                          INICIAR FL lanzaba una segunda sesion en el
        //                          mismo proceso y el servidor contaba el
        //                          telefono dos veces en FedAvg.
        //   5 / 1.4  (2026-08-15)  pendiente J: el cliente manda su client_id
        //                          en las metricas de fit para que el servidor
        //                          detecte dos conexiones del mismo telefono.
        //   6 / 1.5  (2026-08-15)  pendientes A2 y E: permiso de exencion de
        //                          bateria (que faltaba en el manifiesto),
        //                          pantalla de proteccion, vigia de
        //                          WorkManager y diario de eventos.
        //                          BD v5 -> v6.
        //   7 / 1.6  (2026-08-15)  pendiente C: filtro de actividad con umbral
        //                          autocalibrado por dispositivo (k*suelo de
        //                          ruido propio), aplicado ANTES de capWindows;
        //                          TARGET_SESSIONS 20 -> 30; e impostores
        //                          emparejados por energia para neutralizar el
        //                          confound de dominio con HMOG.
        //   8 / 1.7  (2026-08-15)  modo de ablacion dictado por el SERVIDOR
        //                          (GET /api/model/info -> "ablation"), para
        //                          medir el efecto del filtro y del emparejado
        //                          con el MISMO APK en las dos condiciones.
        //   9 / 1.8  (2026-08-16)  tercer modo de ablacion 'matched_off'
        //                          (filtro SI, emparejado NO): aisla el efecto
        //                          del emparejado con el mismo conjunto de
        //                          ventanas y las mismas genuinas de test.
        //  10 / 1.9  (2026-08-16)  pendiente G: modo 'peer'. Los impostores
        //                          pueden venir de OTRO PARTICIPANTE REAL,
        //                          cargados desde filesDir (no van en el APK).
        //                          La app aborta si el modo del servidor y el
        //                          pool cargado no coinciden.
        //  11 / 1.10 (2026-08-16)  recogida del corpus de fondo y captura de
        //                          impostor en el MISMO dispositivo:
        //                          - exportacion de la BASE (zip por
        //                            FileProvider) en vez del CSV, que cargaba
        //                            las dos tablas enteras en memoria;
        //                          - pantalla de captura etiquetada, con
        //                            seudonimo de participante, 30 s de
        //                            aclimatacion y rafagas de 3 min iguales a
        //                            las automaticas;
        //                          - WindowSegmenter EXCLUYE del conjunto
        //                            genuino los tramos de otras personas.
        //                          BD v6 -> v7 (tabla labeled_sessions).
        versionCode = 11
        versionName = "1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Backend en el PC de desarrollo, misma red Wi-Fi que el teléfono.
        // serverHost DEBE llevar el puerto: ModelInfoFetcher construye la URL
        // como "http://$SERVER_HOST/api/model/info", sin añadir ninguno.
        // flowerHost va sin puerto: FlowerGrpcClient.connect usa 8080 por defecto.
        // Si cambia la IP del PC (`ipconfig`), actualiza ambas y recompila.
        val serverHost = "192.168.0.6:5000"
        val flowerHost = "192.168.0.6"

        buildConfigField("String", "SERVER_HOST", "\"$serverHost\"")
        buildConfigField("String", "FLOWER_HOST", "\"$flowerHost\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // SELECT_TF_OPS arrastra libtensorflowlite_flex_jni.so, que pesa 48-77 MB
    // POR ARQUITECTURA. Un APK universal sale a ~297 MB, inviable para
    // repartirlo por WhatsApp o correo a los usuarios de prueba.
    //
    // Con splits, Gradle emite un APK por ABI:
    //   arm64-v8a    ~75 MB   cualquier móvil de los últimos ~9 años
    //   armeabi-v7a  ~57 MB   móviles antiguos de 32 bits
    //   x86_64       ~88 MB   emuladores (no sirven para datos reales: no
    //                         tienen acelerómetro ni giroscopio de verdad)
    //
    // `isUniversalApk = false`: el universal no lo quiere nadie. Gradle instala
    // automáticamente el que corresponde al dispositivo conectado, así que
    // installDebug y connectedDebugAndroidTest siguen funcionando igual.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
        resources.excludes += "META-INF/io.netty.versions.properties"
    }
    androidResources {
        // El .tflite se mapea en memoria con openFd(), lo que exige que el
        // asset esté sin comprimir dentro del APK. Los .bin de background se
        // dejan igual para no pagar la descompresión al arrancar.
        noCompress += listOf("tflite", "bin")
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.64.0" }
        create("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") { option("lite") }
                create("grpckt") { option("lite") }
            }
            task.builtins { 
                create("java") { option("lite") }
                create("kotlin") { option("lite") } 
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Red de seguridad del servicio de recoleccion (pendiente A2).
    implementation(libs.work.runtime.ktx)
    
    // --- TFLite (LiteRT) — inferencia y entrenamiento on-device ---
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-api:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
        exclude(group = "org.tensorflow", module = "tensorflow-lite-support-api")
    }
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    // --- gRPC para Kotlin/Android — cliente Flower manual ---
    implementation("io.grpc:grpc-okhttp:1.64.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-protobuf-lite:1.64.0")
    implementation("io.grpc:grpc-android:1.64.0")
    implementation("com.google.protobuf:protobuf-kotlin-lite:3.25.3")

    // --- HTTP para REST /api/model/info ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}