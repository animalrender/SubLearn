plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing comes from the environment only (CI secrets → files outside the tree); without it
// the release build falls back to the debug key so that anyone can reproduce the build (REQ-6).
val releaseKeystorePath: String? = System.getenv("SUBLEARN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.sublearn.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.sublearn.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en", "fa")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SUBLEARN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SUBLEARN_KEY_ALIAS")
                keyPassword = System.getenv("SUBLEARN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Shrunk but not obfuscated (see proguard-rules.pro and docs/DECISIONS.md D-21): stack traces
            // from the first public builds must be readable without a mapping file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per common ABI plus a universal one; the release workflow attaches all four to the
    // GitHub Release. ML Kit's translation engine is the only native code, so the split is what
    // keeps the per-device download small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:settings"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":core:subtitles"))
    implementation(project(":core:translate"))
    implementation(project(":core:ai"))
    implementation(project(":core:security"))
    implementation(project(":core:lexicon"))
    implementation(project(":feature:home"))
    implementation(project(":feature:player"))
    implementation(project(":feature:learn"))
    implementation(project(":feature:words"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.serialization.json)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.koin.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    // The BOM pins the test artifacts too: AGP's test configurations do not inherit the platform
    // from `implementation`, so without these two lines ui-test-junit4 resolves with no version.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
