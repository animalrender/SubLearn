package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Providers for the AI helper (AI-1). Each is a thin client over a documented REST endpoint, and
 * each is replaceable: the UI only ever talks to [AiProvider].
 */
class GeminiProvider(private val client: HttpJsonClient) : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val defaultModel = "gemini-2.0-flash"
    override val defaultBaseUrl = "https://generativelanguage.googleapis.com"

    override fun isConfigured(apiKey: String?, baseUrl: String?) = !apiKey.isNullOrBlank()

    override fun describeRequest(request: AiRequest, baseUrl: String?): String =
        "POST ${requestUrl(request, baseUrl ?: defaultBaseUrl)} - the key travels in the x-goog-api-key header"

    override suspend fun complete(request: AiRequest, apiKey: String, baseUrl: String?): AppResult<AiAnswer> {
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val contents: JsonElement = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", textParts(request.userPrompt))
            })
        }
        val body: JsonObject = buildJsonObject {
            put("contents", contents)
            request.systemPrompt?.let { system ->
                val instruction: JsonElement = buildJsonObject { put("parts", textParts(system)) }
                put("systemInstruction", instruction)
            }
            val generationConfig: JsonElement = buildJsonObject {
                put("temperature", request.temperature.toDouble())
                put("maxOutputTokens", request.maxOutputTokens)
            }
            put("generationConfig", generationConfig)
        }
        return postAndParse(
            client = client,
            url = requestUrl(request, baseUrl ?: defaultBaseUrl),
            headers = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
            body = body,
            providerId = id,
            model = model,
        ) { root ->
            val text = root.optArray("candidates")
                ?.firstOrNull()
                ?.asObject()
                ?.optObject("content")
                ?.optArray("parts")
                ?.mapNotNull { it.asObject()?.optString("text") }
                ?.joinToString("")
                .orEmpty()
            val finish = root.optArray("candidates")?.firstOrNull()?.asObject()?.optString("finishReason")
            val tokens = root.optObject("usageMetadata")?.optString("totalTokenCount")?.toIntOrNull()
            text to (finish to tokens)
        }
    }

    private fun textParts(text: String): JsonElement = buildJsonArray { add(buildJsonObject { put("text", text) }) }

    private fun requestUrl(request: AiRequest, baseUrl: String): String {
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val trimmed = baseUrl.trimEnd('/')
        AiEndpointPolicy.requireHttps(trimmed)
        return "$trimmed/v1beta/models/$model:generateContent"
    }
}

/** OpenAI and any OpenAI-compatible gateway (the CUSTOM provider token uses this with a base URL). */
class OpenAiCompatibleProvider(
    private val client: HttpJsonClient,
    override val id: String = "openai",
    override val displayName: String = "OpenAI",
    override val defaultModel: String = "gpt-4o-mini",
    override val defaultBaseUrl: String = "https://api.openai.com",
    private val useApiKeyHeader: Boolean = false,
) : AiProvider {
    override fun isConfigured(apiKey: String?, baseUrl: String?) = !apiKey.isNullOrBlank()

    override fun describeRequest(request: AiRequest, baseUrl: String?): String =
        "POST ${(baseUrl ?: defaultBaseUrl).trimEnd('/')}/v1/chat/completions with bearer auth"

    override suspend fun complete(request: AiRequest, apiKey: String, baseUrl: String?): AppResult<AiAnswer> {
        val base = (baseUrl ?: defaultBaseUrl).trimEnd('/')
        AiEndpointPolicy.requireHttps(base)
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val messages: JsonElement = buildJsonArray {
            request.systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
            add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
        }
        val body: JsonObject = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("temperature", request.temperature.toDouble())
            put("max_tokens", request.maxOutputTokens)
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (useApiKeyHeader) put("api-key", apiKey) else put("Authorization", "Bearer $apiKey")
        }
        return postAndParse(
            client = client,
            url = "$base/v1/chat/completions",
            headers = headers,
            body = body,
            providerId = id,
            model = model,
        ) { root ->
            val text = root.optArray("choices")
                ?.firstOrNull()
                ?.asObject()
                ?.optObject("message")
                ?.optString("content")
                .orEmpty()
            val finish = root.optArray("choices")?.firstOrNull()?.asObject()?.optString("finish_reason")
            val tokens = root.optObject("usage")?.optString("total_tokens")?.toIntOrNull()
            text to (finish to tokens)
        }
    }
}

class AnthropicProvider(private val client: HttpJsonClient) : AiProvider {
    override val id = "anthropic"
    override val displayName = "Anthropic Claude"
    override val defaultModel = "claude-3-5-haiku-latest"
    override val defaultBaseUrl = "https://api.anthropic.com"

    override fun isConfigured(apiKey: String?, baseUrl: String?) = !apiKey.isNullOrBlank()

    override fun describeRequest(request: AiRequest, baseUrl: String?): String =
        "POST ${(baseUrl ?: defaultBaseUrl).trimEnd('/')}/v1/messages with x-api-key and anthropic-version"

    override suspend fun complete(request: AiRequest, apiKey: String, baseUrl: String?): AppResult<AiAnswer> {
        val base = (baseUrl ?: defaultBaseUrl).trimEnd('/')
        AiEndpointPolicy.requireHttps(base)
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val messages: JsonElement = buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", request.userPrompt) })
        }
        val body: JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxOutputTokens)
            put("temperature", request.temperature.toDouble())
            put("messages", messages)
            request.systemPrompt?.let { put("system", it) }
        }
        return postAndParse(
            client = client,
            url = "$base/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
            ),
            body = body,
            providerId = id,
            model = model,
        ) { root ->
            val text = root.optArray("content")
                ?.mapNotNull { block -> block.asObject()?.takeIf { it.optString("type") == "text" }?.optString("text") }
                ?.joinToString("")
                .orEmpty()
            val finish = root.optString("stop_reason")
            val tokens = root.optObject("usage")?.optString("output_tokens")?.toIntOrNull()
            text to (finish to tokens)
        }
    }
}

/** Endpoint guard: https only, no credentials smuggled into the URL. */
object AiEndpointPolicy {
    fun isSafe(url: String): Boolean {
        val normalized = url.trim()
        if (!normalized.startsWith("https://")) return false
        val host = normalized.removePrefix("https://").substringBefore('/')
        return host.isNotBlank() && !normalized.contains('@') && !host.contains(" ")
    }

    fun requireHttps(url: String) {
        if (!isSafe(url)) {
            throw IllegalArgumentException("AI endpoints must be an https URL without credentials")
        }
    }
}

/** Shared request/parse/error mapping so every provider behaves the same way in the UI. */
internal suspend fun postAndParse(
    client: HttpJsonClient,
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
    providerId: String,
    model: String,
    parse: (JsonObject) -> Pair<String, Pair<String?, Int?>>,
): AppResult<AiAnswer> {
    val response = try {
        client.post(url, headers, body.toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        return AppResult.failure(SubLearnError.from(t))
    }
    if (!response.isSuccessful) return AppResult.failure(httpError(response.status, response.body))
    val root = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return AppResult.failure(SubLearnError(SubLearnError.Kind.Unknown, "the provider returned a non-JSON body"))
    val parsed = runCatching { parse(root) }
    if (parsed.isFailure) {
        return AppResult.failure(
            SubLearnError(SubLearnError.Kind.Unknown, "could not read the provider response", parsed.exceptionOrNull()),
        )
    }
    val (text, meta) = parsed.getOrThrow()
    if (text.isBlank()) {
        val message = root.optObject("error")?.optString("message") ?: root.optString("message")
        return AppResult.failure(
            SubLearnError(
                SubLearnError.Kind.Unknown,
                message?.takeIf { it.isNotBlank() } ?: "the provider returned no text",
            ),
        )
    }
    return AppResult.success(
        AiAnswer(
            text = text.trim(),
            providerId = providerId,
            model = model,
            finishReason = meta.first,
            usageTokens = meta.second,
            raw = root,
        ),
    )
}

internal fun httpError(status: Int, body: String): SubLearnError {
    val message = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        root.optObject("error")?.optString("message") ?: root.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return when (status) {
        401, 403 -> SubLearnError(SubLearnError.Kind.Unauthorized, message ?: "the API key was rejected (HTTP $status)")
        429 -> SubLearnError(SubLearnError.Kind.RateLimited, message ?: "rate limited by the provider (HTTP $status)")
        404 -> SubLearnError(SubLearnError.Kind.NotFound, message ?: "model or endpoint not found (HTTP $status)")
        in 500..599 -> SubLearnError(SubLearnError.Kind.NetworkUnavailable, message ?: "provider error (HTTP $status)")
        else -> SubLearnError(SubLearnError.Kind.Unknown, message ?: "HTTP $status")
    }
}

internal fun JsonObject.optString(key: String): String? {
    return (this[key] as? JsonPrimitive)?.content
}

internal fun JsonObject.optObject(key: String): JsonObject? = this[key].asObject()

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
