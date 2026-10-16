plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:subtitles"))
    // Sense ranking is the join between the two halves of the NLP stack: the lexicon types
    // (`SenseRanker`, `SenseCandidate`) come from `core:nlp`, and the models that refine them come
    // from here. `core:nlp` does not depend on this module, so the edge is one-way.
    api(project(":core:nlp"))
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
