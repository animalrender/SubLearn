package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.subtitles.SubtitleBlock

/**
 * Chooses a provider and runs one question for the AI button, including the pause/resume contract
 * the UI relies on (AI-2). Keeping the selection here means a new provider is one entry in [byId].
 */
class AiAssistant(
    private val providers: Map<String, AiProvider>,
    private val defaultProviderId: String = GeminiProvider().id,
) {
    constructor(
        gemini: AiProvider,
        openAi: AiProvider,
        anthropic: AiProvider,
        custom: AiProvider,
    ) : this(
        providers = listOf(gemini, openAi, anthropic, custom).associateBy { it.id },
        defaultProviderId = gemini.id,
    )

    val availableProviderIds: List<String> get() = providers.keys.sorted()

    fun byId(id: String?): AiProvider = providers[id?.lowercase()] ?: providers.getValue(defaultProviderId)

    fun isConfigured(id: String?, apiKey: String?, baseUrl: String?): Boolean = byId(id).let { provider ->
        provider.isConfigured(apiKey, baseUrl) && (baseUrl.isNullOrBlank() || AiEndpointPolicy.isSafe(baseUrl))
    }

    suspend fun ask(
        providerId: String?,
        request: AiRequest,
        apiKey: String?,
        baseUrl: String?,
    ): AppResult<AiAnswer> {
        val provider = byId(providerId)
        if (apiKey.isNullOrBlank()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.Unauthorized, "no API key is configured for ${provider.displayName}"))
        }
        if (baseUrl != null && baseUrl.isNotBlank() && !AiEndpointPolicy.isSafe(baseUrl)) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.UnsupportedFormat, "the base URL must be an https address"))
        }
        return runCatching { provider.complete(request, apiKey, baseUrl?.takeIf { it.isNotBlank() }) }
            .fold(
                onSuccess = { it },
                onFailure = { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    AppResult.failure(SubLearnError.from(throwable))
                },
            )
    }

    /** Assembles the request from the user's template and the current block (AI-2, AI-4). */
    fun buildRequest(config: AiAskConfig, block: SubtitleBlock?, selectedText: String?): AiRequest {
        val request = AiPromptRequest(
            userTemplate = config.promptTemplate,
            systemTemplate = config.systemTemplate,
            selectedText = selectedText,
            block = block,
            context = config.contextBlocks,
            title = config.title,
            learningLanguage = config.learningLanguage,
            nativeLanguage = config.nativeLanguage,
            level = config.level,
            mode = config.mode,
            temperaturePercent = config.temperaturePercent,
            maxOutputTokens = config.maxOutputTokens,
            model = config.model,
        )
        return AiPromptBuilder.build(request)
    }
}

/** Everything the assistant needs from settings, as a plain value. */
data class AiAskConfig(
    val promptTemplate: String,
    val systemTemplate: String? = null,
    val contextBlocks: List<SubtitleBlock> = emptyList(),
    val title: String? = null,
    val learningLanguage: String = "English",
    val nativeLanguage: String = "Persian",
    val level: String = "B1",
    val mode: String = "entertainment",
    val temperaturePercent: Int = 30,
    val maxOutputTokens: Int = 700,
    val model: String? = null,
)
