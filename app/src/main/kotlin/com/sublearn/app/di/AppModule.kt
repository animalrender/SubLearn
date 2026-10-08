package com.sublearn.app.di

import com.sublearn.app.AndroidLogcatLogger
import com.sublearn.core.ai.AnthropicProvider
import com.sublearn.core.ai.AiAssistant
import com.sublearn.core.ai.GeminiProvider
import com.sublearn.core.ai.HttpJsonClient
import com.sublearn.core.ai.OkHttpJsonClient
import com.sublearn.core.ai.OpenAiCompatibleProvider
import com.sublearn.core.common.SubLearnLogger
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.RecentVideoRepository
import com.sublearn.core.data.RoomMyWordsRepository
import com.sublearn.core.data.RoomRecentVideoRepository
import com.sublearn.core.data.SubLearnDatabase
import com.sublearn.core.lexicon.FrequencyListImporter
import com.sublearn.core.lexicon.TextResourceReader
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.player.Media3PlayerController
import com.sublearn.core.player.PlayerController
import com.sublearn.core.player.PositionSink
import com.sublearn.core.security.SecretStore
import com.sublearn.core.security.productionSecretStore
import com.sublearn.core.settings.DataStoreSettingsStorage
import com.sublearn.core.settings.DefaultSettingsRepository
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.settings.SettingsStorage
import com.sublearn.core.subtitles.DefaultSubtitleRepository
import com.sublearn.core.subtitles.SubtitleFileSource
import com.sublearn.core.subtitles.SubtitleRepository
import com.sublearn.core.translate.MlKitTranslationProvider
import com.sublearn.core.translate.RoomTranslationCacheStore
import com.sublearn.core.translate.TranslationCacheStore
import com.sublearn.core.translate.TranslationOptions
import com.sublearn.core.translate.TranslationProvider
import com.sublearn.core.translate.TranslationService
import com.sublearn.feature.home.HomeViewModel
import com.sublearn.feature.learn.LearnViewModel
import com.sublearn.feature.player.PlayerViewModel
import com.sublearn.feature.player.SafSubtitleFileSource
import com.sublearn.feature.player.SafTextResourceReader
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
    single {
        val settings: SettingsRepository = get()
        RoomTranslationCacheStore(
            dao = get<SubLearnDatabase>().translationCache(),
            maxEntries = settings.settings.value.translation.cacheMaxEntries,
        )
    } bind TranslationCacheStore::class

    // subtitles
    single { SafSubtitleFileSource(androidContext()) } bind SubtitleFileSource::class
    single<SubtitleRepository> { DefaultSubtitleRepository(get()) }
    single { SafTextResourceReader(androidContext()) } bind TextResourceReader::class
    single { FrequencyListImporter(get()) }
    single { WordLevelSource(get()) }

    // translation: ML Kit on device only, never a network endpoint behind this seam
    single<TranslationProvider> { MlKitTranslationProvider(logger = get()) }
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
    viewModel { LearnViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel {
        PlayerViewModel(
            application = get(),
            controller = get(),
            settingsRepository = get(),
            subtitles = get(),
            fileSource = get(),
            translation = get(),
            myWords = get(),
            recentVideos = get(),
            wordLevels = get(),
            ai = get(),
            secrets = get(),
        )
    }
}
