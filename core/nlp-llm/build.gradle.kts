plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sublearn.core.nlp.llm"
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
    // MediaPipe's LLM Inference API runs the LiteRT-LM `.task` bundles from the SMART profile.
    implementation(libs.mediapipe.tasks.genai)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
