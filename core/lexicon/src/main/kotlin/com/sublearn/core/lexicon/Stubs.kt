package com.sublearn.core.lexicon

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.FeatureFlag
import com.sublearn.core.common.NotImplementedInThisBuild
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.subtitles.SubtitleBlock

/**
 * LATER capabilities are declared here so the NOW build compiles against the same shapes the future
 * features will use. Every stub throws [NotImplementedInThisBuild] and every entry in the UI that
 * could call it is behind [FeatureFlag] and shows "Coming soon" instead.
 */

/** Part-of-speech, phrasal verb, collocation and idiom detection (SUB-5, LRN-3). */
data class WordAnalysis(
    val partOfSpeech: String? = null,
    val isPhrasalVerb: Boolean = false,
    val isCollocation: Boolean = false,
    val isIdiom: Boolean = false,
    val lemma: String? = null,
    val level: CefrLevel? = null,
)

interface WordAnalyzer {
    val feature: FeatureFlag get() = FeatureFlag.POS_ANALYSIS

    suspend fun analyze(block: SubtitleBlock, word: String): AppResult<WordAnalysis>
}

class NotImplementedWordAnalyzer : WordAnalyzer {
    override suspend fun analyze(block: SubtitleBlock, word: String): AppResult<WordAnalysis> =
        throw NotImplementedInThisBuild(feature)
}

/** Offline dictionary import and lookup (LRN-1 full details, scope switch item 1). */
data class DictionaryEntry(
    val headword: String,
    val meanings: List<String>,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val phrasalVerbs: List<String> = emptyList(),
    val collocations: List<String> = emptyList(),
    val idioms: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val cefr: CefrLevel? = null,
    val categories: List<String> = emptyList(),
    val pronunciationUs: String? = null,
    val pronunciationUk: String? = null,
) {
    val isEmpty: Boolean
        get() = meanings.isEmpty() && synonyms.isEmpty() && phrasalVerbs.isEmpty() && idioms.isEmpty()
}

interface DictionaryProvider {
    val feature: FeatureFlag get() = FeatureFlag.OFFLINE_DICTIONARY

    suspend fun lookup(word: String): AppResult<DictionaryEntry?>

    suspend fun isAvailable(): Boolean
}

class NotImplementedDictionaryProvider : DictionaryProvider {
    override suspend fun lookup(word: String): AppResult<DictionaryEntry?> = throw NotImplementedInThisBuild(feature)

    override suspend fun isAvailable(): Boolean = false
}

/** On-device speech to text for videos with no subtitles. */
interface SpeechToText {
    val feature: FeatureFlag get() = FeatureFlag.SPEECH_TO_TEXT

    suspend fun generate(videoUri: String, language: String): AppResult<List<SubtitleBlock>>
}

class NotImplementedSpeechToText : SpeechToText {
    override suspend fun generate(videoUri: String, language: String): AppResult<List<SubtitleBlock>> =
        throw NotImplementedInThisBuild(feature)
}

/** GitHub Releases update check (scope switch item 3). */
data class UpdateInfo(val versionName: String, val url: String, val notes: String)

interface UpdateChecker {
    val feature: FeatureFlag get() = FeatureFlag.UPDATE_CHECKER

    suspend fun check(currentVersion: String): AppResult<UpdateInfo?>
}

class NotImplementedUpdateChecker : UpdateChecker {
    override suspend fun check(currentVersion: String): AppResult<UpdateInfo?> = throw NotImplementedInThisBuild(feature)
}

/** Automatic level detection (LRN-2, LATER). */
interface LevelDetector {
    val feature: FeatureFlag get() = FeatureFlag.LEVEL_DETECTION

    suspend fun detect(sampleWords: List<String>): AppResult<CefrLevel>
}

class NotImplementedLevelDetector : LevelDetector {
    override suspend fun detect(sampleWords: List<String>): AppResult<CefrLevel> = throw NotImplementedInThisBuild(feature)
}

/** Quiz over My Words (scope switch item 2). */
interface QuizEngine {
    val feature: FeatureFlag get() = FeatureFlag.QUIZ
}

class NotImplementedQuizEngine : QuizEngine
