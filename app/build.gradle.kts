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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val serverHost = "alb-backend-tesis-656342325.us-east-2.elb.amazonaws.com"
        val flowerHost = "3.144.252.195"

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