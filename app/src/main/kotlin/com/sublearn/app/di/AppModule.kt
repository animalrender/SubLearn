package com.sublearn.app.di

import com.sublearn.app.AndroidLogcatLogger
import com.sublearn.core.ai.AiAssistant
import com.sublearn.core.ai.EmbedderLoader
import com.sublearn.core.ai.LlmLoader
import com.sublearn.core.ai.ModelDownloader
import com.sublearn.core.ai.ModelStore
import com.sublearn.core.ai.NlpModelInstaller
import com.sublearn.core.ai.NlpModelSelfTest
import com.sublearn.core.ai.ProfileTier
import com.sublearn.core.ai.ProfiledNlpEngineCache
import com.sublearn.core.ai.AnthropicProvider
import com.sublearn.core.ai.GeminiProvider
import com.sublearn.core.ai.HttpJsonClient
import com.sublearn.core.ai.OkHttpJsonClient
import com.sublearn.core.ai.OpenAiCompatibleProvider
import com.sublearn.core.common.SubLearnLogger
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.RecentVideoRepository
import com.sublearn.core.data.RoomInstalledPackRepository
import com.sublearn.core.data.RoomMyWordsRepository
import com.sublearn.core.data.RoomRecentVideoRepository
import com.sublearn.core.data.SubLearnDatabase
import com.sublearn.core.lexicon.DictionaryProvider
import com.sublearn.core.lexicon.FrequencyListImporter
import com.sublearn.core.lexicon.ImportedDictionaryProvider
import com.sublearn.core.lexicon.LegacyV1Importer
import com.sublearn.core.lexicon.PackImporter
import com.sublearn.core.lexicon.PackLexiconAccess
import com.sublearn.core.lexicon.TextResourceReader
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.models.DeviceCapabilityReader
import com.sublearn.core.models.ProcessResourceReader
import com.sublearn.core.models.PackDownloader
import com.sublearn.core.nlp.LexiconAccess
import com.sublearn.core.nlp.llm.LiteRtLlmLoader
import com.sublearn.core.nlp.onnx.OnnxEmbedderLoader
import com.sublearn.core.player.Media3PlayerController
import com.sublearn.core.player.PlayerController
import com.sublearn.core.player.PositionSink
import com.sublearn.core.security.SecretStore
import com.sublearn.core.security.productionSecretStore
import com.sublearn.core.settings.DataStoreSettingsStorage
import com.sublearn.core.settings.DefaultSettingsRepository
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.settings.SettingsStorage
import com.sublearn.core.settings.TranslationSourceKind
import com.sublearn.core.subtitles.DefaultSubtitleRepository
import com.sublearn.core.subtitles.SubtitleFileSource
import com.sublearn.core.subtitles.SubtitleRepository
import com.sublearn.core.translate.LegacyV1TranslationEngine
import com.sublearn.core.translate.MlKitTranslationEngine
import com.sublearn.core.translate.MlKitTranslationProvider
import com.sublearn.core.translate.OnlineGoogleTranslationEngine
import com.sublearn.core.translate.PackGlossaryResult
import com.sublearn.core.translate.PackTranslationEngine
import com.sublearn.core.translate.RoomTranslationCacheStore
import com.sublearn.core.translate.TranslationCacheStore
import com.sublearn.core.translate.TranslationModelManager
import com.sublearn.core.translate.TranslationOptions
import com.sublearn.core.translate.TranslationOrchestrator
import com.sublearn.core.translate.TranslationProvider
import com.sublearn.core.translate.TranslationService
import com.sublearn.feature.home.HomeViewModel
import com.sublearn.feature.learn.LearnViewModel
import com.sublearn.feature.player.PlayerViewModel
import com.sublearn.feature.player.SafSubtitleFileSource
import com.sublearn.feature.player.SafTextResourceReader
import com.sublearn.feature.settings.DownloadsViewModel
import com.sublearn.feature.settings.LabViewModel
import com.sublearn.feature.settings.SettingsViewModel
import com.sublearn.feature.words.WordsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The single wiring point of the app.
 *
 * Two rules, enforced in review: a feature never constructs a core type itself, and nothing outside
 * this file names a concrete implementation. Swapping the player, the AI provider or the storage
 * backend is therefore a one-file change (docs/ARCHITECTURE.md).
 */
fun subLearnModules(): List<Module> = listOf(coreModule(), featureModule())

private fun coreModule(): Module = module {
    single<SubLearnLogger> { AndroidLogcatLogger() }

    // storage
    single { DataStoreSettingsStorage.create(androidContext()) } bind SettingsStorage::class
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single { SubLearnDatabase.create(androidContext()) }
    single<SecretStore> { productionSecretStore(androidContext()) }

    // repositories
    single { RoomRecentVideoRepository(get<SubLearnDatabase>().recentVideos()) } bind RecentVideoRepository::class
    single { RoomMyWordsRepository(get<SubLearnDatabase>().myWords()) } bind MyWordsRepository::class
    single { RoomInstalledPackRepository(get<SubLearnDatabase>().installedPacks()) } bind InstalledPackRepository::class
    single {
        val settings: SettingsRepository = get()
        RoomTranslationCacheStore(
            dao = get<SubLearnDatabase>().translationCache(),
            maxEntries = settings.settings.value.translation.cacheMaxEntries,
        )
    } bind TranslationCacheStore::class

    // lexicon & packs
    single { PackLexiconAccess(get()) } bind LexiconAccess::class
    single { LegacyV1Importer(androidContext(), get(), get()) }
    single { PackImporter(androidContext(), get(), get(), get()) }
    single { PackDownloader(androidContext().filesDir.resolve("packs")) }
    single<DictionaryProvider> { ImportedDictionaryProvider(get()) }

    // subtitles
    single { SafSubtitleFileSource(androidContext()) } bind SubtitleFileSource::class
    single<SubtitleRepository> { DefaultSubtitleRepository(get()) }
    single { SafTextResourceReader(androidContext()) } bind TextResourceReader::class
    single { FrequencyListImporter(get()) }
    single { WordLevelSource(get(), get()) }

    // translation
    single<TranslationProvider> { MlKitTranslationProvider(logger = get()) }
    // One manager for the offline translation model, shared by the settings screen, the downloads
    // screen and the player: the model is downloaded once and every screen shows the same state
    // (D-81).
    single {
        val settings: SettingsRepository = get()
        TranslationModelManager(
            provider = get(),
            pair = {
                val languages = settings.settings.value.languages
                languages.learningLanguage to languages.nativeLanguage
            },
        )
    }

    // optional nlp models: one store, one downloader, one installer (Phase 23, D-74)
    single { ModelStore(androidContext().filesDir.resolve("ai-models")) }
    single { ModelDownloader() }
    single { NlpModelInstaller(store = get(), downloader = get<ModelDownloader>()::download) }

    // What the phone can do; the accuracy profiles are checked against it (D-81).
    single { DeviceCapabilityReader(androidContext()) }
    // What this process is using right now; the subtitle lab shows it beside its results (D-87).
    single { ProcessResourceReader(androidContext()) }
    // The "Test" button next to an installed accuracy model (D-86): the same loaders the player
    // uses, so a pass here means the player's ranker will open the model too.
    single { NlpModelSelfTest(store = get(), embedderLoader = get(), llmLoader = get()) }

    // The engines are registered by asking them whether their native library loads. A build or a
    // device where it does not keeps the pure-Kotlin ranker and never offers a profile it cannot run.
    single<EmbedderLoader> { OnnxEmbedderLoader(preferNnapi = false, maxTokens = 256) }
    single<LlmLoader> { LiteRtLlmLoader(androidContext(), preferGpu = false, maxTokens = 256) }

    // The ranker an accuracy profile promises (Phase 23, D-76). The cache keeps that profile's
    // resident models open across subtitle lines and reopens them when the profile changes, so a
    // tapped word never pays for an ONNX session.
    single {
        val settings: SettingsRepository = get()
        ProfiledNlpEngineCache(
            store = get(),
            profileProvider = {
                ProfileTier.fromToken(settings.settings.value.smartLearning.accuracyProfile.name)
            },
            embedderLoader = get(),
            llmLoader = get(),
        )
    }
    // Which engines this process can run is decided in `NlpRuntimeBootstrap`, called from
    // `SubLearnApplication.onCreate`: a lazy Koin `single` would make the answer depend on whether
    // something happened to request a dependency first.
    single {
        val settings: SettingsRepository = get()
        TranslationService(
            provider = get(),
            cache = get<TranslationCacheStore>(),
            options = {
                val value = settings.settings.value
                TranslationOptions(
                    sourceLanguage = value.languages.learningLanguage,
                    targetLanguage = value.languages.nativeLanguage,
                    useCache = value.translation.cacheEnabled,
                    cacheMaxEntries = value.translation.cacheMaxEntries,
                    translateWholeBlockFirst = value.translation.translateWholeBlockFirst,
                )
            },
        )
    }

    single {
        val lexicon: PackLexiconAccess = get()
        val settings: SettingsRepository = get()
        val packEngine = PackTranslationEngine(
            isPackReady = { lexicon.hasActiveEnFaPack() },
            lookupFn = { q ->
                val full = lexicon.lookupFullEntry(q)
                full?.let {
                    PackGlossaryResult(
                        headword = it.headword,
                        lemma = it.headword.lowercase(),
                        cefr = it.cefr?.name,
                        phonetic = it.pronunciationUs ?: it.pronunciationUk,
                        primaryGloss = it.meanings.firstOrNull().orEmpty(),
                        posGlosses = if (it.meanings.isNotEmpty()) listOf(it.pos to it.meanings) else emptyList(),
                        examples = it.examples,
                        lexiconEntryId = it.entryId,
                    )
                }
            },
        )
        val legacyEngine = LegacyV1TranslationEngine(
            isLegacyReady = { lexicon.hasActiveLegacyV1Pack() },
            lookupFn = { q ->
                val full = lexicon.lookupLegacyV1(q)
                full?.let {
                    PackGlossaryResult(
                        headword = it.headword,
                        lemma = it.headword.lowercase(),
                        cefr = it.cefr?.name,
                        phonetic = it.pronunciationUs ?: it.pronunciationUk,
                        primaryGloss = it.meanings.firstOrNull().orEmpty(),
                        posGlosses = if (it.meanings.isNotEmpty()) listOf(it.pos to it.meanings) else emptyList(),
                        examples = it.examples,
                        lexiconEntryId = it.entryId,
                    )
                }
            },
        )
        val mlKitEngine = MlKitTranslationEngine(get())
        val onlineEngine = OnlineGoogleTranslationEngine(
            settingsProvider = { settings.settings.value.translation },
        )
        TranslationOrchestrator(
            engines = mapOf(
                TranslationSourceKind.PACK to packEngine,
                TranslationSourceKind.ML_KIT to mlKitEngine,
                TranslationSourceKind.LEGACY_V1 to legacyEngine,
                TranslationSourceKind.ONLINE_GOOGLE to onlineEngine,
            ),
            cache = get<TranslationCacheStore>(),
            settingsProvider = { settings.settings.value.translation },
        )
    }

    // ai
    single<HttpJsonClient> { OkHttpJsonClient() }
    single {
        val client: HttpJsonClient = get()
        AiAssistant(
            gemini = GeminiProvider(client),
            openAi = OpenAiCompatibleProvider(client),
            anthropic = AnthropicProvider(client),
            custom = OpenAiCompatibleProvider(client, id = "custom", displayName = "Custom", defaultBaseUrl = ""),
        )
    }

    // player: one instance for the process, so navigating away never stops playback
    single<PlayerController> {
        val settings: SettingsRepository = get()
        val current = settings.settings.value.player
        Media3PlayerController(
            context = androidContext(),
            logger = get(),
            positionSink = PositionSink { uri, position ->
                get<RecentVideoRepository>().savePosition(uri, position)
            },
            initialDecoder = current.decoder,
            seekBackMs = current.seekStepMs,
            seekForwardMs = current.seekStepMs,
        )
    }
}

private fun featureModule(): Module = module {
    viewModel { HomeViewModel(get()) }
    viewModel { WordsViewModel(get(), get(), get()) }
    viewModel { LearnViewModel(get(), get(), get()) }
    viewModel {
        DownloadsViewModel(
            settingsRepository = get(),
            installedPacksRepo = get(),
            packImporter = get(),
            legacyImporter = get(),
            downloader = get(),
            lexiconAccess = get(),
            modelInstaller = get(),
            modelSelfTest = get(),
            engineCache = get(),
            translationModels = get(),
            deviceCapabilities = get(),
            context = androidContext(),
        )
    }
    viewModel {
        LabViewModel(
            settingsRepository = get(),
            subtitles = get(),
            installedPacksRepo = get(),
            lexiconAccess = get(),
            engineCache = get(),
            resources = get(),
            context = androidContext(),
        )
    }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), androidContext(), get()) }
    viewModel {
        PlayerViewModel(
            application = get(),
            controller = get(),
            settingsRepository = get(),
            subtitles = get(),
            fileSource = get(),
            translation = get(),
            translationModels = get(),
            myWords = get(),
            recentVideos = get(),
            wordLevels = get(),
            ai = get(),
            secrets = get(),
            lexiconAccessProvider = { get<LexiconAccess>() },
            senseRankerProvider = { get<ProfiledNlpEngineCache>().senseRanker() },
            orchestrator = get(),
            phraseSpanCacheDao = get<SubLearnDatabase>().phraseSpans(),
        )
    }
}
