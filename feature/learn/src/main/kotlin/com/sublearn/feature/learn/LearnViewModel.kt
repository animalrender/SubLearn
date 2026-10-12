package com.sublearn.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.WordStatus
import com.sublearn.core.lexicon.DictionaryEntry
import com.sublearn.core.lexicon.DictionaryProvider
import com.sublearn.core.lexicon.LegacyV1Importer
import com.sublearn.core.lexicon.PackImporter
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.ModelCatalogParser
import com.sublearn.core.models.ModelKind
import com.sublearn.core.models.ModelRuntimeGuard
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.models.PackDownloader
import com.sublearn.core.models.ProfileBenchmarkReport
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LearnViewModel(
    private val settingsRepository: SettingsRepository,
    wordLevels: WordLevelSource,
    private val installedPacksRepo: InstalledPackRepository? = null,
    private val packImporter: PackImporter? = null,
    private val legacyImporter: LegacyV1Importer? = null,
    private val downloader: PackDownloader? = null,
    private val dictionaryProvider: DictionaryProvider? = null,
    private val myWords: MyWordsRepository? = null,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val levelState = wordLevels.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, WordLevelSource.Status())

    val installedPacks: StateFlow<List<InstalledPackEntity>> = installedPacksRepo?.observeAll()
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        ?: MutableStateFlow(emptyList())

    val downloadStates: StateFlow<Map<String, PackDownloadState>> =
        downloader?.states ?: MutableStateFlow(emptyMap())

    val catalogEntries: List<ModelCatalogEntry> = ModelCatalogParser.bundledDefault().visibleEntries()

    private val runtimeGuard = ModelRuntimeGuard()
    private val _benchmarkReport = MutableStateFlow<ProfileBenchmarkReport?>(null)
    val benchmarkReport: StateFlow<ProfileBenchmarkReport?> = _benchmarkReport.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Dictionary search state (D-55, D-58)
    private val _dictQuery = MutableStateFlow("")
    val dictQuery: StateFlow<String> = _dictQuery.asStateFlow()

    private val _dictDirection = MutableStateFlow("en-fa")
    val dictDirection: StateFlow<String> = _dictDirection.asStateFlow()

    private val _dictEntry = MutableStateFlow<DictionaryEntry?>(null)
    val dictEntry: StateFlow<DictionaryEntry?> = _dictEntry.asStateFlow()

    fun setMode(mode: LearningMode) = update { it.copy(learning = it.learning.copy(mode = mode)) }

    fun setLevel(level: CefrLevel) = update { it.copy(learning = it.learning.copy(manualLevel = level)) }

    fun setPopupCount(count: Int) = update { it.copy(learning = it.learning.copy(maxPopupCards = count.coerceIn(1, 10))) }

    fun setPopupLifetime(ms: Long) = update { it.copy(learning = it.learning.copy(popupLifetimeMs = ms.coerceIn(1_500L, 15_000L))) }

    fun setAccuracyProfile(profile: AccuracyProfileToken) {
        val report = runtimeGuard.benchmark(profile)
        _benchmarkReport.value = report
        update { it.copy(smartLearning = it.smartLearning.copy(accuracyProfile = report.recommendedProfile)) }
    }

    fun dismissOnboarding() = update {
        it.copy(smartLearning = it.smartLearning.copy(completedOnboarding = true))
    }

    fun importV2Pack(uriString: String) {
        val importer = packImporter ?: return
        viewModelScope.launch {
            val res = importer.importUri(uriString)
            res.fold(
                onSuccess = { out ->
                    _statusMessage.value = "Imported ${out.packId} (${out.meta?.entryCount ?: 0} entries)"
                },
                onFailure = { err ->
                    _statusMessage.value = err.message
                },
            )
        }
    }

    fun importLegacyV1Pack(uriString: String) {
        val importer = legacyImporter ?: return
        viewModelScope.launch {
            val res = importer.importUri(uriString)
            res.fold(
                onSuccess = { out ->
                    _statusMessage.value = "Imported ${out.packId} (${out.meta?.entryCount ?: 0} entries)"
                },
                onFailure = { err ->
                    _statusMessage.value = err.message
                },
            )
        }
    }

    fun activatePack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            installedPacksRepo?.setEnabled(pack.packId, !pack.enabled)
        }
    }

    fun verifyPack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            val file = File(pack.localPath)
            if (!file.exists()) {
                _statusMessage.value = "File missing: ${pack.localPath}"
                return@launch
            }
            val sha = PackDownloader.computeSha256(file)
            if (pack.sha256.isBlank() || sha.equals(pack.sha256, ignoreCase = true)) {
                _statusMessage.value = "SHA-256 verified (${sha.take(12)}…)"
            } else {
                _statusMessage.value = "SHA-256 mismatch!"
            }
        }
    }

    fun deletePack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            runCatching { File(pack.localPath).delete() }
            installedPacksRepo?.remove(pack.packId)
        }
    }

    fun downloadCatalogEntry(entry: ModelCatalogEntry) {
        val dl = downloader ?: return
        viewModelScope.launch {
            val res = dl.download(entry)
            res.fold(
                onSuccess = { downloadedFile ->
                    if (entry.kind == ModelKind.LEXICON || entry.kind == ModelKind.DICT_FA_EN) {
                        packImporter?.importFile(downloadedFile)
                    }
                    _statusMessage.value = "Downloaded ${entry.nameEn}"
                },
                onFailure = { err ->
                    _statusMessage.value = err.message
                },
            )
        }
    }

    fun setDictDirection(direction: String) {
        _dictDirection.value = direction
        val q = _dictQuery.value
        if (q.isNotBlank()) searchDictionary(q)
    }

    fun searchDictionary(query: String) {
        _dictQuery.value = query
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _dictEntry.value = null
            return
        }
        viewModelScope.launch {
            val entry = runCatching { dictionaryProvider?.lookup(trimmed)?.getOrNull() }.getOrNull()
            _dictEntry.value = entry
        }
    }

    fun bookmarkDictionaryEntry(entry: DictionaryEntry) {
        val repo = myWords ?: return
        viewModelScope.launch {
            val primaryGloss = entry.senses.values.flatten().firstOrNull()
            repo.mark(
                MarkedWord(
                    id = 0L,
                    word = entry.word,
                    isPhrase = entry.word.contains(' '),
                    translation = primaryGloss,
                    contextText = entry.examples.firstOrNull(),
                    contextTranslation = null,
                    sourceTitle = "Dictionary",
                    sourceStartMs = -1L,
                    status = WordStatus.LEARNING,
                    level = entry.cefr?.takeIf { it != CefrLevel.UNKNOWN }?.name,
                    note = entry.synonyms.take(5).joinToString(", ").ifBlank { null },
                    markedAt = System.currentTimeMillis(),
                    reviewCount = 0,
                    lemma = entry.word.lowercase(),
                    sourceKind = "PACK",
                ),
            )
            _statusMessage.value = "Saved '${entry.word}' to My Words"
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
