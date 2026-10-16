plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sublearn.core.nlp.onnx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:ai"))
    api(project(":core:nlp"))
    api(libs.coroutines.core)
    implementation(libs.koin.core)
    // ONNX Runtime executes the quantised sentence encoders. 1.22.0 is the current release; the
    // AAR bundles the native providers for every ABI the app ships.
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // The embedder is exercised on the JVM: `onnxruntime-android` cannot be resolved there, so the
    // tests cover everything that does not need the native session (tokenizer, layout, pooling).
    testImplementation(project(":core:ai"))
}
