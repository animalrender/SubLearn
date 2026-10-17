package com.sublearn.core.ai

/** The UI awaits a diagnostic without hosting an untrusted native model in its own process. */
fun interface ModelTestRunner {
    suspend fun run(modelId: String): ModelSelfTestReport
}
