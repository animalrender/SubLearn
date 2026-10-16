package com.sublearn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.lexicon.LegacyV1Importer
import com.sublearn.core.lexicon.PackImporter
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.ModelCatalogParser
import com.sublearn.core.models.ModelKind
import com.sublearn.core.models.ModelRuntimeGuard
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.models.PackDownloader
import com.sublearn.core.models.ProfileBenchmarkReport
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/** Settings owns optional resources so the future reading tab never initializes download or model controls. */
class ResourcesViewModel(
    private val settingsRepository: SettingsRepository,
    private val installedPacksRepo: InstalledPackRepository,
    private val packImporter: PackImporter,
    private val legacyImporter: LegacyV1Importer,
    private val downloader: PackDownloader,
    private val lexiconAccess: com.sublearn.core.lexicon.PackLexiconAccess,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val installedPacks: StateFlow<List<InstalledPackEntity>> = installedPacksRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadStates: StateFlow<Map<String, PackDownloadState>> =
        downloader.states

    val catalogEntries: List<ModelCatalogEntry> = ModelCatalogParser.bundledDefault().visibleEntries()

    private val runtimeGuard = ModelRuntimeGuard()
    private val _benchmarkReport = MutableStateFlow<ProfileBenchmarkReport?>(null)
    val benchmarkReport: StateFlow<ProfileBenchmarkReport?> = _benchmarkReport.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            lexiconAccess.ensureInitialized()
        }
    }

    fun setAccuracyProfile(profile: AccuracyProfileToken) {
        val report = runtimeGuard.benchmark(profile)
        _benchmarkReport.value = report
        update { it.copy(smartLearning = it.smartLearning.copy(accuracyProfile = report.recommendedProfile)) }
    }

    fun dismissOnboarding() = update {
        it.copy(smartLearning = it.smartLearning.copy(completedOnboarding = true))
    }

    fun importV2Pack(uriString: String) {
        val importer = packImporter
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
        val importer = legacyImporter
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
            installedPacksRepo.setEnabled(pack.packId, !pack.enabled)
            lexiconAccess.reloadFromRepository()
        }
    }

    fun verifyPack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            val file = File(pack.localPath)
            if (!file.exists()) {
                _statusMessage.value = "File missing: ${pack.localPath}"
                return@launch
            }
            val sha = withContext(Dispatchers.IO) { PackDownloader.computeSha256(file) }
            if (pack.sha256.isBlank()) {
                _statusMessage.value = "No reference hash; computed SHA-256: $sha"
            } else if (sha.equals(pack.sha256, ignoreCase = true)) {
                _statusMessage.value = "SHA-256 verified (${sha.take(12)}…)"
            } else {
                _statusMessage.value = "SHA-256 mismatch!"
            }
        }
    }

    fun deletePack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val file = File(pack.localPath)
                !file.exists() || file.delete()
            }
            if (!removed) {
                _statusMessage.value = "Could not remove ${pack.titleEn}"
                return@launch
            }
            installedPacksRepo.remove(pack.packId)
            lexiconAccess.reloadFromRepository()
        }
    }

    fun downloadCatalogEntry(entry: ModelCatalogEntry) {
        val dl = downloader
        viewModelScope.launch {
            val res = dl.download(entry)
            val downloadedFile = res.getOrNull()
            if (downloadedFile != null) {
                if (entry.kind == ModelKind.LEXICON || entry.kind == ModelKind.DICT_FA_EN) {
                    val imported = packImporter.importFile(downloadedFile)
                    if (imported.getOrNull() == null) {
                        _statusMessage.value = imported.errorOrNull()?.message
                        return@launch
                    }
                    lexiconAccess.reloadFromRepository()
                }
                _statusMessage.value = "Downloaded ${entry.nameEn}"
            } else {
                _statusMessage.value = res.errorOrNull()?.message
            }
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
