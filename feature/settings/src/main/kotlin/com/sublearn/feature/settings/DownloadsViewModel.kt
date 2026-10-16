package com.sublearn.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.ai.NlpModelCatalog
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.NlpModelInstaller
import com.sublearn.core.ai.NlpModelSelfTest
import com.sublearn.core.ai.ProfileAvailability
import com.sublearn.core.ai.ProfileTier
import com.sublearn.core.ai.ProfiledNlpEngineCache
import com.sublearn.core.ai.SmartProfileSelector
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.designsystem.R
import com.sublearn.core.lexicon.LegacyV1Importer
import com.sublearn.core.lexicon.PackImporter
import com.sublearn.core.lexicon.PackKind
import com.sublearn.core.lexicon.PackLexiconAccess
import com.sublearn.core.models.DeviceCapabilityReader
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.ModelCatalogParser
import com.sublearn.core.models.ModelKind
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.models.PackDownloader
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.translate.TranslationModelManager
import com.sublearn.core.translate.TranslationModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the Downloads screen shows: the offline translation model, the offline lexicon
 * packs, the optional accuracy models and the accuracy profile.
 *
 * One view model for the whole screen is the point. The three things the user downloads — the
 * translation model, a lexicon pack and an accuracy model — used to live in three different
 * screens with three different notions of "installed", which is what made the app look like it
 * had three download systems and left the user guessing where to go (D-81).
 *
 * @property settingsRepository the settings tree; the accuracy profile is stored in it
 * @property installedPacksRepo the installed lexicon packs
 * @property packImporter imports a v2 pack from a file
 * @property legacyImporter imports a legacy v1 pack from a file
 * @property downloader fetches the pack files from the release
 * @property lexiconAccess the open lexicon, reloaded after a pack changes
 * @property modelInstaller downloads and removes the optional accuracy models
 * @property modelSelfTest opens an installed accuracy model and runs one probe through it (D-86)
 * @property engineCache the player's open profile engine, dropped when the installed models change
 * @property translationModels the offline translation model, shared with the whole app
 * @property deviceCapabilities what the phone can do, for the profile advice
 * @property context used to resolve the status messages
 */
class DownloadsViewModel(
    private val settingsRepository: SettingsRepository,
    private val installedPacksRepo: InstalledPackRepository,
    private val packImporter: PackImporter,
    private val legacyImporter: LegacyV1Importer,
    private val downloader: PackDownloader,
    private val lexiconAccess: PackLexiconAccess,
    private val modelInstaller: NlpModelInstaller,
    private val modelSelfTest: NlpModelSelfTest,
    private val engineCache: ProfiledNlpEngineCache,
    private val translationModels: TranslationModelManager,
    private val deviceCapabilities: DeviceCapabilityReader,
    private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val installedPacks: StateFlow<List<InstalledPackEntity>> = installedPacksRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadStates: StateFlow<Map<String, PackDownloadState>> = downloader.states

    /** The offline translation model, the same state the player and the translation screen show. */
    val translationModelState: StateFlow<TranslationModelState> = translationModels.state

    /**
     * The catalog entries this screen can install: the offline packs.
     *
     * The accuracy models are not in this list. They are listed by [NlpModelCatalog] and
     * installed by [NlpModelInstaller], which fetches every file a model needs — the catalog
     * entry carries only the primary file, so downloading it from here produced a model the
     * runtime could not open.
     */
    val catalogEntries: List<ModelCatalogEntry> = ModelCatalogParser.bundledDefault()
        .visibleEntries()
        .filter { it.kind == ModelKind.LEXICON || it.kind == ModelKind.DICT_FA_EN }

    private val _nlpModels = MutableStateFlow(nlpModelRows())
    val nlpModels: StateFlow<List<NlpModelRow>> = _nlpModels.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * Catalog entries whose file has arrived and is being imported into the database. The
     * downloader's own state says COMPLETED at that point, and a card that says "installed" while
     * the import is still writing is the kind of early success AGENTS.md forbids.
     */
    private val _importingEntries = MutableStateFlow<Set<String>>(emptySet())
    val importingEntries: StateFlow<Set<String>> = _importingEntries.asStateFlow()

    /** The self-test of one accuracy model: running, or its report (D-86). */
    private val _selfTest = MutableStateFlow<SelfTestUi?>(null)
    val selfTest: StateFlow<SelfTestUi?> = _selfTest.asStateFlow()

    /**
     * The running download of each catalog entry, so the stop button can cancel it. Concurrent
     * because a job's completion handler runs on whichever thread finished it.
     */
    private val packJobs = ConcurrentHashMap<String, Job>()

    /** The running install of each accuracy model, so the stop button can cancel it. */
    private val nlpJobs = ConcurrentHashMap<String, Job>()

    /** What each accuracy profile needs before it can run on this device. */
    private val _profileAvailability = MutableStateFlow<Map<AccuracyProfileToken, ProfileAvailability>>(emptyMap())
    val profileAvailability: StateFlow<Map<AccuracyProfileToken, ProfileAvailability>> = _profileAvailability.asStateFlow()

    /** The strongest profile this device can run with the models it has. */
    private val _recommendedProfile = MutableStateFlow<AccuracyProfileToken>(AccuracyProfileToken.BASIC)
    val recommendedProfile: StateFlow<AccuracyProfileToken> = _recommendedProfile.asStateFlow()

    /** How much memory this device has, which is what a profile is checked against. */
    private val _deviceRamMb = MutableStateFlow(0)
    val deviceRamMb: StateFlow<Int> = _deviceRamMb.asStateFlow()

    /** The size of the translation model download, when the pair's size is known. */
    fun translationModelSize(): Long? = translationModels.downloadSizeBytes()

    /** The user dismissed the status line. */
    fun clearStatus() {
        _statusMessage.value = null
    }

    /** The files an accuracy model is made of, with their URLs, for the details dialog. */
    fun modelLayout(modelId: String): ModelFileLayout? = modelInstaller.layoutFor(modelId)

    /** The user closed the self-test dialog. */
    fun dismissSelfTest() {
        if (_selfTest.value is SelfTestUi.Done) _selfTest.value = null
    }

    /**
     * The installed pack a catalog entry corresponds to, if any.
     *
     * Catalog ids and installed pack ids are different namespaces (the catalog names a release
     * file, the pack's own metadata names the pack), so the match is by kind: one lexicon pack and
     * one Persian→English dictionary are all the catalog offers.
     */
    fun installedPackFor(entry: ModelCatalogEntry, installed: List<InstalledPackEntity>): InstalledPackEntity? {
        val kind = when (entry.kind) {
            ModelKind.LEXICON -> PackKind.LEXICON_EN_FA
            ModelKind.DICT_FA_EN -> PackKind.DICT_FA_EN
            else -> return null
        }
        return installed.firstOrNull { it.kind == kind.id }
    }

    init {
        viewModelScope.launch {
            lexiconAccess.ensureInitialized()
        }
        refresh()
    }

    /** Re-reads everything that can change without the user asking for it. */
    fun refresh() {
        viewModelScope.launch {
            translationModels.refresh()
            _nlpModels.value = nlpModelRows()
            updateProfileAdvice()
        }
    }

    // ---------------------------------------------------------------- the translation model

    /** Downloads the offline translation model; the same action the player offers. */
    fun downloadTranslationModel() {
        viewModelScope.launch {
            translationModels.download().fold(
                onSuccess = {
                    _statusMessage.value = context.getString(R.string.downloads_translation_installed)
                },
                onFailure = { error ->
                    _statusMessage.value = context.getString(
                        R.string.downloads_translation_failed,
                        error.message.orEmpty(),
                    )
                },
            )
        }
    }

    /** Removes the offline translation model from the device. */
    fun removeTranslationModel() {
        viewModelScope.launch {
            translationModels.remove().fold(
                onSuccess = {
                    _statusMessage.value = context.getString(R.string.downloads_translation_removed)
                },
                onFailure = { error ->
                    _statusMessage.value = context.getString(
                        R.string.downloads_translation_failed,
                        error.message.orEmpty(),
                    )
                },
            )
        }
    }

    // ---------------------------------------------------------------- the accuracy models

    /**
     * Download and install one accuracy model, reporting progress as it arrives.
     *
     * The job is kept so [cancelNlpModel] can stop it. The installer runs on IO and reports
     * through a callback; `ensureActive` inside that callback is how a cancel reaches a blocking
     * download, and the installer deletes the half-written directory on the way out.
     */
    fun installNlpModel(modelId: String) {
        if (nlpJobs[modelId]?.isActive == true) return
        val name = NlpModelCatalog.byId[modelId]?.name ?: modelId
        val job = viewModelScope.launch {
            setNlpState(modelId) { NlpModelState.Downloading(null) }
            val result = try {
                withContext(Dispatchers.IO) {
                    // The callback is not suspending, so the context is captured here and checked
                    // there; cancelling the job throws out of the installer on the next chunk.
                    val downloadContext = coroutineContext
                    modelInstaller.install(modelId) { progress ->
                        downloadContext.ensureActive()
                        setNlpState(modelId) { NlpModelState.Downloading(progress) }
                    }
                }
            } catch (cancelled: CancellationException) {
                setNlpState(modelId) { NlpModelState.Available }
                _statusMessage.value = context.getString(R.string.downloads_model_stopped, name)
                throw cancelled
            }

            result.fold(
                onSuccess = {
                    engineCache.invalidate()
                    _nlpModels.value = nlpModelRows()
                    updateProfileAdvice()
                    _statusMessage.value = context.getString(R.string.downloads_model_installed, name)
                },
                onFailure = { error ->
                    setNlpState(modelId) { NlpModelState.Error(error.message.orEmpty()) }
                    _statusMessage.value = context.getString(
                        R.string.downloads_model_failed,
                        name,
                        error.message.orEmpty(),
                    )
                },
            )
        }
        nlpJobs[modelId] = job
        job.invokeOnCompletion { if (nlpJobs[modelId] === job) nlpJobs.remove(modelId) }
    }

    /** Stop a running accuracy-model download; the partial files are removed. */
    fun cancelNlpModel(modelId: String) {
        nlpJobs[modelId]?.cancel()
    }

    /**
     * Open an installed accuracy model and run one probe through it (D-86).
     *
     * The run happens on the default dispatcher, not Main: loading an ONNX session or a LiteRT
     * model takes seconds and the dialog has to keep drawing its spinner. Only one test runs at a
     * time; the loaders own native memory and two at once is the out-of-memory case the test is
     * meant to catch, not cause.
     */
    fun testNlpModel(modelId: String) {
        if (_selfTest.value is SelfTestUi.Running) return
        val name = NlpModelCatalog.byId[modelId]?.name ?: modelId
        viewModelScope.launch {
            _selfTest.value = SelfTestUi.Running(modelId, name)
            val report = withContext(Dispatchers.Default) {
                // The player's engine may hold this very model; two copies of a 500 MB bundle is
                // the out-of-memory the test is meant to catch, not cause. The next tap reopens it.
                engineCache.invalidate()
                modelSelfTest.run(modelId)
            }
            _selfTest.value = SelfTestUi.Done(name, report)
        }
    }

    /** Remove one accuracy model from the device. */
    fun deleteNlpModel(modelId: String) {
        viewModelScope.launch {
            val name = NlpModelCatalog.byId[modelId]?.name ?: modelId
            val removed = withContext(Dispatchers.IO) {
                engineCache.invalidate()
                modelInstaller.delete(modelId)
            }
            _nlpModels.value = nlpModelRows()
            updateProfileAdvice()
            _statusMessage.value = context.getString(
                if (removed) R.string.downloads_model_removed else R.string.downloads_model_remove_failed,
                name,
            )
        }
    }

    /** Read every model's state from the filesystem, so the cards never show a stale value. */
    private fun nlpModelRows(): List<NlpModelRow> = modelInstaller.availableModels().map { model ->
        val state = if (modelInstaller.isInstalled(model.id)) {
            NlpModelState.Installed(modelInstaller.installedBytes(model.id))
        } else {
            NlpModelState.Available
        }
        NlpModelRow(model, state)
    }

    /** Replace one card's state while a download runs, leaving every other row untouched. */
    private fun setNlpState(modelId: String, transform: (NlpModelState) -> NlpModelState) {
        _nlpModels.value = _nlpModels.value.map { row ->
            if (row.model.id == modelId) row.copy(state = transform(row.state)) else row
        }
    }

    // ---------------------------------------------------------------- the accuracy profile

    /**
     * The profile the user picked is stored as it is.
     *
     * The old code replaced the choice with whatever a benchmark thought the device could handle,
     * so picking the strongest profile silently produced the weakest one. The advice is shown
     * next to the chips instead, and the engine falls back to what it can actually open (D-81).
     */
    fun setAccuracyProfile(profile: AccuracyProfileToken) {
        update { it.copy(smartLearning = it.smartLearning.copy(accuracyProfile = profile)) }
        updateProfileAdvice()
    }

    /** Recomputes what each profile needs on this device. */
    private fun updateProfileAdvice() {
        val device = deviceCapabilities.read()
        _deviceRamMb.value = device.totalRamMb
        val installed = modelInstaller.installedIds()
        val availability = AccuracyProfileToken.entries.associateWith { token ->
            SmartProfileSelector.availability(
                profile = ProfileTier.fromToken(token.name),
                device = device,
                installedModelIds = installed,
            )
        }
        _profileAvailability.value = availability
        val recommended = SmartProfileSelector.select(device, installed)
        _recommendedProfile.value = AccuracyProfileToken.entries.firstOrNull { it.name == recommended.name }
            ?: AccuracyProfileToken.BASIC
    }

    // ---------------------------------------------------------------- the packs

    fun importV2Pack(uriString: String) {
        val importer = packImporter
        viewModelScope.launch {
            val res = importer.importUri(uriString)
            res.fold(
                onSuccess = { out ->
                    _statusMessage.value = context.getString(
                        R.string.downloads_pack_imported,
                        out.packId.orEmpty(),
                        out.meta?.entryCount ?: 0,
                    )
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
                    _statusMessage.value = context.getString(
                        R.string.downloads_pack_imported,
                        out.packId.orEmpty(),
                        out.meta?.entryCount ?: 0,
                    )
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

    /**
     * Checks the installed file against the hash the release published.
     *
     * The hash is of the file that is on the device, which is the decompressed database the
     * importer wrote, so it is compared with the same value the importer recorded.
     */
    fun verifyPack(pack: InstalledPackEntity) {
        viewModelScope.launch {
            val file = File(pack.localPath)
            if (!file.exists()) {
                _statusMessage.value = context.getString(R.string.downloads_pack_missing)
                return@launch
            }
            val sha = withContext(Dispatchers.IO) { PackDownloader.computeSha256(file) }
            _statusMessage.value = if (pack.sha256.isBlank()) {
                context.getString(R.string.downloads_pack_no_hash, sha)
            } else if (sha.equals(pack.sha256, ignoreCase = true)) {
                context.getString(R.string.downloads_pack_verified, sha.take(12))
            } else {
                context.getString(R.string.downloads_pack_mismatch)
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
                _statusMessage.value = context.getString(R.string.downloads_pack_remove_failed, pack.titleEn)
                return@launch
            }
            installedPacksRepo.remove(pack.packId)
            lexiconAccess.reloadFromRepository()
        }
    }

    /**
     * Downloads a pack from the release and imports it. Resumes a stopped download from its
     * partial file; the downloader keeps the `.part` on cancel for exactly that.
     */
    fun downloadCatalogEntry(entry: ModelCatalogEntry) {
        if (packJobs[entry.id]?.isActive == true) return
        val dl = downloader
        val job = viewModelScope.launch {
            val res = try {
                dl.download(entry)
            } catch (cancelled: CancellationException) {
                // The downloader has already marked the entry PAUSED and kept the partial file.
                _statusMessage.value = context.getString(R.string.downloads_pack_stopped, entry.nameEn)
                throw cancelled
            }
            val downloadedFile = res.getOrNull()
            if (downloadedFile != null) {
                if (entry.kind == ModelKind.LEXICON || entry.kind == ModelKind.DICT_FA_EN) {
                    _importingEntries.update { it + entry.id }
                    val imported = try {
                        packImporter.importFile(downloadedFile)
                    } finally {
                        _importingEntries.update { it - entry.id }
                    }
                    if (imported.getOrNull() == null) {
                        _statusMessage.value = imported.errorOrNull()?.message
                        return@launch
                    }
                    lexiconAccess.reloadFromRepository()
                }
                _statusMessage.value = context.getString(R.string.downloads_pack_downloaded, entry.nameEn)
            } else {
                _statusMessage.value = res.errorOrNull()?.message
            }
        }
        packJobs[entry.id] = job
        job.invokeOnCompletion { if (packJobs[entry.id] === job) packJobs.remove(entry.id) }
    }

    /** Stop a running pack download. The partial file stays, and "Resume" continues it. */
    fun cancelCatalogDownload(entry: ModelCatalogEntry) {
        packJobs[entry.id]?.cancel()
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
