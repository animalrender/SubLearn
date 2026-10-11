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
    api(project(":core:nlp"))
    api(libs.coroutines.core)
    implementation(libs.koin.core)
    // ONNX Runtime will be added when the module is activated in phase 14:
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
