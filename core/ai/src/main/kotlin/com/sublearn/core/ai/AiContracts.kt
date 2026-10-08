package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import kotlinx.serialization.json.JsonObject

/**
 * Everything the AI helper needs to answer one question (AI-1..AI-4).
 *
 * The key is passed per call instead of being held here, so this module never touches Android
 * keystore APIs and can be unit tested on the JVM.
 */
data class AiRequest(
    val userPrompt: String,
    val systemPrompt: String? = null,
    val temperature: Float = 0.3f,
    val maxOutputTokens: Int = 700,
    val model: String? = null,
)

data class AiAnswer(
    val text: String,
    val providerId: String,
    val model: String,
    val finishReason: String? = null,
    val usageTokens: Int? = null,
    val raw: JsonObject? = null,
)

interface AiProvider {
    val id: String
    val displayName: String
    val defaultModel: String
    val defaultBaseUrl: String

    /** True when this provider can run with the given configuration (key present, url valid). */
    fun isConfigured(apiKey: String?, baseUrl: String?): Boolean

    suspend fun complete(request: AiRequest, apiKey: String, baseUrl: String?): AppResult<AiAnswer>

    /** Human-readable curl of the request, used by the "test connection" row. Never logs the key. */
    fun describeRequest(request: AiRequest, baseUrl: String?): String
}

/** Minimal JSON transport seam so providers are testable without a socket. */
interface HttpJsonClient {
    suspend fun post(url: String, headers: Map<String, String>, bodyJson: String): HttpJsonResponse
}

data class HttpJsonResponse(val status: Int, val body: String, val contentType: String? = null) {
    val isSuccessful: Boolean get() = status in 200..299
}

/** The four-part answer shape from AI-3, parsed defensively: missing sections stay absent. */
data class AiAnswerSections(
    val meaning: String? = null,
    val whyUsed: String? = null,
    val synonyms: String? = null,
    val elsewhere: String? = null,
    val extra: String? = null,
) {
    val isEmpty: Boolean get() = meaning == null && whyUsed == null && synonyms == null && elsewhere == null && extra == null

    companion object {
        val EMPTY = AiAnswerSections()
    }
}
