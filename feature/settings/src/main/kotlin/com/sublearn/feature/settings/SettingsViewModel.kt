package com.sublearn.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.ai.AiAssistant
import com.sublearn.core.ai.AiRequest
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.lexicon.FrequencyListFormat
import com.sublearn.core.lexicon.FrequencyListImporter
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.security.SecretStore
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.ImportMode
import com.sublearn.core.settings.ImportReport
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.translate.TranslationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the settings screens do beyond reading and writing the tree: importing a word list,
 * storing the API key, exporting settings, checking a provider, clearing caches.
 *
 * Note what is *not* here: no settings value is cached in this class. The tree in
 * [SettingsRepository] is the only state, so a screen left open while another changes a value
 * updates too.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val wordLevels: WordLevelSource,
    private val importer: FrequencyListImporter,
    private val translation: TranslationService,
    private val secrets: SecretStore,
    private val ai: AiAssistant,
    private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /** Imported word-list status, shown on the Level card. */
    val levelState: StateFlow<WordLevelSource.Status> = wordLevels.state

    private val _status = MutableStateFlow<SettingsStatus>(SettingsStatus.Idle)
    val status: StateFlow<SettingsStatus> = _status.asStateFlow()

    private val _modelProgress = MutableStateFlow<Float?>(null)
    val modelProgress: StateFlow<Float?> = _modelProgress.asStateFlow()

    sealed interface SettingsStatus {
        data object Idle : SettingsStatus
        data class Info(val text: String) : SettingsStatus
        data class Error(val text: String) : SettingsStatus
        data class Imported(val count: Int, val warnings: List<String>) : SettingsStatus
        data class ImportReported(val report: ImportReport) : SettingsStatus
    }

    fun updateLayer(
        role: com.sublearn.core.subtitles.TrackRole,
        transform: (com.sublearn.core.settings.SubtitleLayerSettings) -> com.sublearn.core.settings.SubtitleLayerSettings,
    ) {
        update { settings ->
            settings.copy(subtitles = settings.subtitles.updated(role, transform(settings.subtitleLayer(role))))
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(transform) }
                .onFailure { error -> _status.value = SettingsStatus.Error(error.message ?: "the change could not be saved") }
        }
    }

    // ---------------------------------------------------------------- word lists (LRN-2)

    fun importWordList(uriKey: String, rejectedSuffix: String = "") {
        viewModelScope.launch {
            _status.value = SettingsStatus.Info("reading the file")
            importer.import(uriKey).fold(
                onSuccess = { parsed ->
                    wordLevels.replaceImported(parsed.entries, uriKey, parsed.warnings)
                    update { settings -> settings.copy(level = settings.level.copy(frequencyListFileKey = uriKey)) }
                    _status.value = SettingsStatus.Imported(parsed.entries.size, parsed.warnings)
                },
                onFailure = { error -> _status.value = SettingsStatus.Error(error.message ?: "the file could not be read") },
            )
        }
    }

    fun clearWordList() {
        wordLevels.clearImported()
        update { settings -> settings.copy(level = settings.level.copy(frequencyListFileKey = null)) }
        _status.value = SettingsStatus.Info("the imported list is cleared")
    }

    // ---------------------------------------------------------------- AI (AI-4)

    fun saveApiKey(value: String) {
        viewModelScope.launch {
            if (value.isBlank()) {
                settingsRepository.current().ai.keyRef?.let { secrets.delete(it) }
                update { it.copy(ai = it.ai.copy(keyRef = null)) }
                _status.value = SettingsStatus.Info("the key was deleted")
            } else {
                secrets.put(SecretStore.AI_KEY_REF, value)
                update { it.copy(ai = it.ai.copy(keyRef = SecretStore.AI_KEY_REF)) }
                _status.value = SettingsStatus.Info("the key is stored in the device keystore")
            }
        }
    }

    /** One tiny request, so "Test" answers the real question: will this configuration work? */
    fun testConnection() {
        viewModelScope.launch {
            val value = settingsRepository.current().ai
            val key = value.keyRef?.let { runCatching { secrets.get(it) }.getOrNull() }
            val providerId = value.provider.tokenToId()
            if (!ai.isConfigured(providerId, key, value.customBaseUrl.ifBlank { null })) {
                _status.value = SettingsStatus.Error("a key is needed first")
                return@launch
            }
            _status.value = SettingsStatus.Info("asking for a one word answer")
            val request = AiRequest(userPrompt = "Reply with the single word: ready", maxOutputTokens = 16, model = value.model)
            ai.ask(providerId, request, key, value.customBaseUrl.ifBlank { null }).fold(
                onSuccess = { answer -> _status.value = SettingsStatus.Info("answered: ${answer.text.take(60)}") },
                onFailure = { error -> _status.value = SettingsStatus.Error(error.message ?: "the request failed") },
            )
        }
    }

    fun hasKey(): Boolean = settings.value.ai.keyRef != null

    /** Reports a message in the banner; used by the sections that do I/O themselves. */
    fun notify(text: String) {
        _status.value = SettingsStatus.Info(text)
    }

    suspend fun exportText(): String = settingsRepository.exportJson()

    // ---------------------------------------------------------------- caches and models

    fun clearTranslationCache() {
        viewModelScope.launch {
            runCatching { translation.clearCache() }
            _status.value = SettingsStatus.Info("the translation cache is empty")
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _modelProgress.value = 0f
            translation.downloadModel { progress -> _modelProgress.value = progress.fraction }.fold(
                onSuccess = {
                    _modelProgress.value = null
                    _status.value = SettingsStatus.Info("the model is ready")
                },
                onFailure = { error ->
                    _modelProgress.value = null
                    _status.value = SettingsStatus.Error(error.message ?: "the download failed")
                },
            )
        }
    }

    suspend fun modelReady(): Boolean = runCatching { translation.isModelReady() }.getOrDefault(false)

    // ---------------------------------------------------------------- import / export (SET-6)

    fun exportJson(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val text = settingsRepository.exportJson()
            onDone(text)
            _status.value = SettingsStatus.Info("copied. the key is never part of an export")
        }
    }

    fun importJson(text: String, mode: ImportMode = ImportMode.MERGE) {
        viewModelScope.launch {
            settingsRepository.importJson(text, mode).fold(
                onSuccess = { report -> _status.value = SettingsStatus.ImportReported(report) },
                onFailure = { error -> _status.value = SettingsStatus.Error(error.message ?: "import failed") },
            )
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            settingsRepository.reset()
            _status.value = SettingsStatus.Info("every setting is back to its default")
        }
    }

    fun openUrl(url: String) {
        // The injected context is the Application, which may only start an Activity in a new task.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun AiProviderToken.tokenToId(): String = when (this) {
        AiProviderToken.GEMINI -> "gemini"
        AiProviderToken.OPENAI -> "openai"
        AiProviderToken.ANTHROPIC -> "anthropic"
        AiProviderToken.CUSTOM -> "custom"
    }
}
