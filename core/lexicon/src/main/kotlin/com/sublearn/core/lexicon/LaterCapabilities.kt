package com.sublearn.core.lexicon

import com.sublearn.core.common.FeatureFlag
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.WordStatus
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LevelProviderToken
import com.sublearn.core.settings.LevelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the imported word list and answers level questions for the whole app (LRN-2).
 *
 * The list lives in memory and is re-imported from the file the user picked; nothing is shipped in
 * the APK, because no permissively licensed CEFR word list could be verified (docs/DECISIONS.md).
 */
class WordLevelSource(
    private val myWords: MyWordsRepository,
) {
    private val _imported = MutableStateMap()
    private val _state = MutableStateFlow(Status())

    val state: StateFlow<Status> = _state.asStateFlow()

    data class Status(
        val importedWords: Int = 0,
        val warnings: List<String> = emptyList(),
        val source: String? = null,
    )

    /** Replaces the imported list; call after [FrequencyListImporter] succeeds. */
    fun replaceImported(entries: Map<String, CefrLevel>, source: String?, warnings: List<String> = emptyList()) {
        _imported.replaceAll(entries)
        _state.value = Status(importedWords = entries.size, warnings = warnings, source = source)
    }

    fun clearImported() {
        _imported.replaceAll(emptyMap())
        _state.value = Status()
    }

    suspend fun provider(settings: LevelSettings, manualLevel: CefrLevel): WordLevelProvider {
        val known = suspend { myWords.statusWords(WordStatus.KNOWN) }
        val frequency = FrequencyListLevelProvider(_imported.snapshot(), fallback = null)
        return when (settings.provider) {
            LevelProviderToken.MY_WORDS -> ChainedWordLevelProvider(
                listOf(
                    KnownWordsLevelProvider(known, settings.assumedLevelOfUnknownWords),
                    frequency,
                    ManualLevelProvider(manualLevel),
                ),
            )

            LevelProviderToken.MANUAL -> ManualLevelProvider(manualLevel)
            LevelProviderToken.IMPORTED_FREQUENCY_LIST -> ChainedWordLevelProvider(listOf(frequency, ManualLevelProvider(manualLevel)))

            // Automatic detection is LATER; until then the manual level answers, never a guess.
            LevelProviderToken.AUTOMATIC -> ChainedWordLevelProvider(listOf(frequency, ManualLevelProvider(manualLevel)))
        }
    }

    /** Tiny concurrent-ish map holder so the imported list can be read from any thread. */
    private class MutableStateMap {
        private val delegate = java.util.concurrent.ConcurrentHashMap<String, CefrLevel>()

        fun replaceAll(entries: Map<String, CefrLevel>) {
            delegate.keys.toList().forEach { delegate.remove(it) }
            delegate.putAll(entries)
        }

        fun snapshot(): Map<String, CefrLevel> = HashMap(delegate)

        operator fun get(key: String): CefrLevel? = delegate[key]
    }
}

/**
 * One place that reports which LATER capabilities exist, so a disabled section is obvious in the UI
 * and in tests. Extending SubLearn means adding an implementation here and flipping the flag.
 */
object LaterCapabilities {
    data class Entry(val flag: FeatureFlag, val title: String, val detail: String)

    val entries: List<Entry> = listOf(
        Entry(FeatureFlag.YOUTUBE, "YouTube", "Browse, search and play YouTube with dual subtitles."),
        Entry(FeatureFlag.PDF_LEARNING, "PDF and images", "Read and study text outside the player."),
        Entry(FeatureFlag.OFFLINE_DICTIONARY, "Dictionary", "Import an offline dictionary and show full entries on the word card."),
        Entry(FeatureFlag.LEVEL_DETECTION, "Level", "Detect the CEFR level from what the user watches."),
        Entry(FeatureFlag.QUIZ, "Quiz", "Spaced repetition over My Words."),
        Entry(FeatureFlag.UPDATE_CHECKER, "Updates", "Check GitHub Releases for a newer build."),
        Entry(FeatureFlag.SPEECH_TO_TEXT, "Subtitles from audio", "Generate subtitles on device when a video has none."),
        Entry(FeatureFlag.POS_ANALYSIS, "Word analysis", "Colour verbs, phrasal verbs and collocations automatically."),
        Entry(FeatureFlag.ON_DEVICE_AI, "On-device AI", "Ask the assistant without a network."),
        Entry(FeatureFlag.EXTRA_LANGUAGES, "More languages", "Ship UI and font presets for more learning languages."),
    )

    fun statusFor(flag: FeatureFlag, overrides: Map<String, Boolean> = emptyMap()): Boolean =
        FeatureFlag.isEnabled(flag, overrides)

    /** The stub objects handed to Koin while the real ones do not exist. */
    fun wordAnalyzer(): WordAnalyzer = NotImplementedWordAnalyzer()

    fun dictionaryProvider(): DictionaryProvider = NotImplementedDictionaryProvider()

    fun speechToText(): SpeechToText = NotImplementedSpeechToText()

    fun updateChecker(): UpdateChecker = NotImplementedUpdateChecker()

    fun levelDetector(): LevelDetector = NotImplementedLevelDetector()

    fun quizEngine(): QuizEngine = NotImplementedQuizEngine()
}
