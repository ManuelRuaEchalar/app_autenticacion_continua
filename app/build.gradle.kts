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
        versionCode = 2
        versionName = "1.1"

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