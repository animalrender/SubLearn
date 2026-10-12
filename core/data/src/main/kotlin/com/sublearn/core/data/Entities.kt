package com.sublearn.core.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** A local or streamed video the user opened, used by Home and by process-death restore. */
@Entity(
    tableName = "recent_videos",
    indices = [Index("lastOpenedAt"), Index("title")],
)
data class RecentVideoEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** SAF document flag so Home can re-open it; never a raw filesystem credential. */
    val isPlayable: Boolean = true,
    val takenAt: Long = System.currentTimeMillis(),
)

/** My Words (SUB-5, LRN-1, MWE-19..23). A marked word or phrase plus the line it came from. */
@Entity(
    tableName = "my_words",
    indices = [Index("wordLower"), Index("markedAt"), Index(unique = true, value = ["wordLower", "surface"])],
)
data class MyWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val word: String,
    val wordLower: String,
    /** MULTI_WORD marks a phrase selection; WORD a single token. */
    val kind: String = KIND_WORD,
    val translation: String? = null,
    val contextText: String? = null,
    val contextTranslation: String? = null,
    val sourceUri: String? = null,
    val sourceTitle: String? = null,
    val sourceStartMs: Long = 0L,
    val status: String = STATUS_LEARNING,
    val level: String? = null,
    val note: String? = null,
    val surface: String = SURFACE_SUBTITLE,
    val markedAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long = 0L,
    val reviewCount: Int = 0,
    /** v2 columns: PHRASAL | IDIOM | COLLOCATION, or null for single words. */
    val phraseType: String? = null,
    /** Base lemma or canonical lexicon key (e.g. "give" or "give up") for lemma-aware highlighting. */
    val lemma: String? = null,
    /** Optional entry ID in the active lexicon pack. */
    val lexiconEntryId: Long? = null,
    /** Surface tokens when the phrase was separated in the subtitle (e.g. "picked ... up"). */
    val surfaceParts: String? = null,
    /** End timestamp of the source cue. */
    val sourceCueEndMs: Long = 0L,
    /** Provenance of the saved translation (PACK, LEGACY_V1, ML_KIT, ONLINE_GOOGLE). */
    val sourceKind: String? = null,
) {
    companion object {
        const val KIND_WORD = "WORD"
        const val KIND_MULTI_WORD = "MULTI_WORD"
        const val STATUS_NEW = "NEW"
        const val STATUS_LEARNING = "LEARNING"
        const val STATUS_KNOWN = "KNOWN"
        const val SURFACE_SUBTITLE = "SUBTITLE"
        const val SURFACE_LIST = "WORDS_LIST"
    }
}

/**
 * Translation cache. Offline-first means a re-watched video must not re-run ML Kit or online lookup
 * for the same lines, and the cache also keeps translations readable when a model is removed.
 */
@Entity(tableName = "translation_cache", indices = [Index("createdAt"), Index("hits")])
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: String,
    val createdAt: Long = System.currentTimeMillis(),
    val hits: Int = 0,
)

/** Full-text index over My Words; content-synced so the rows stay the single source of truth. */
@Fts4(contentEntity = MyWordEntity::class)
@Entity(tableName = "my_words_fts")
data class MyWordFts(
    val word: String,
    val translation: String,
    val contextText: String,
    val note: String,
)

/**
 * Installed dictionary/lexicon packs and AI models on device (Phase 10 / D-58).
 */
@Entity(
    tableName = "installed_packs",
    indices = [Index("kind"), Index("enabled")],
)
data class InstalledPackEntity(
    @PrimaryKey val packId: String,
    val kind: String,
    val version: String,
    val titleEn: String,
    val titleFa: String,
    val localPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val entryCount: Int = 0,
    val sourceLang: String = "en",
    val targetLang: String = "fa",
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
)

/**
 * Cached phrase analysis spans per subtitle document and lexicon/analyzer version (D-32, D-46).
 */
@Entity(
    tableName = "phrase_span",
    primaryKeys = ["sourceKey", "packId", "packVersion", "analyzerVersion", "spanId"],
    indices = [Index(value = ["sourceKey", "blockId"])],
)
data class PhraseSpanCacheEntity(
    val sourceKey: String,
    val packId: String,
    val packVersion: String,
    val analyzerVersion: Int,
    val spanId: String,
    val blockId: Long,
    val startChar: Int,
    val endChar: Int,
    val tokensCsv: String,
    val kind: String,
    val headword: String,
    val lemmaKey: String,
    val confidence: Float,
    val separable: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)
