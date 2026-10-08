package com.sublearn.core.data

import androidx.room.ColumnInfo
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

/** My Words (SUB-5, LRN-1). A marked word or phrase plus the line it came from. */
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
 * Translation cache. Offline-first means a re-watched video must not re-run ML Kit for the same
 * lines, and the cache also keeps translations readable when the model is later removed.
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
