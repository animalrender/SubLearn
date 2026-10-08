package com.sublearn.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Domain shape for a marked word; keeps Room types out of the UI (GEN-6). */
data class MarkedWord(
    val id: Long,
    val word: String,
    val isPhrase: Boolean,
    val translation: String?,
    val contextText: String?,
    val contextTranslation: String?,
    val sourceTitle: String?,
    /** Where to jump back to; null when the word came from a list rather than a video. */
    val sourceUri: String? = null,
    val sourceStartMs: Long,
    val status: WordStatus,
    val level: String?,
    val note: String?,
    val markedAt: Long,
    val reviewCount: Int,
)

enum class WordStatus {
    NEW,
    LEARNING,
    KNOWN,
    ;

    companion object {
        fun fromKey(key: String?): WordStatus = entries.firstOrNull { it.name == key } ?: NEW
    }
}

fun MyWordEntity.toDomain(): MarkedWord = MarkedWord(
    id = id,
    word = word,
    isPhrase = kind == MyWordEntity.KIND_MULTI_WORD,
    translation = translation,
    contextText = contextText,
    contextTranslation = contextTranslation,
    sourceTitle = sourceTitle,
    sourceUri = sourceUri,
    sourceStartMs = sourceStartMs,
    status = WordStatus.fromKey(status),
    level = level,
    note = note,
    markedAt = markedAt,
    reviewCount = reviewCount,
)

fun MarkedWord.toEntity(surface: String = MyWordEntity.SURFACE_SUBTITLE): MyWordEntity = MyWordEntity(
    id = id,
    word = word,
    wordLower = WordKey.lower(word),
    kind = if (isPhrase) MyWordEntity.KIND_MULTI_WORD else MyWordEntity.KIND_WORD,
    translation = translation,
    contextText = contextText,
    contextTranslation = contextTranslation,
    sourceTitle = sourceTitle,
    sourceUri = sourceUri,
    sourceStartMs = sourceStartMs,
    status = status.name,
    level = level,
    note = note,
    surface = surface,
    markedAt = markedAt,
    reviewCount = reviewCount,
)

/**
 * Normalisation used for both storage and lookup: Farsi text and English text need different
 * handling, and a word marked from a subtitle must match the same word seen again in another line.
 */
object WordKey {
    fun lower(word: String): String = normalize(word)

    /**
     * Lowercases, trims, removes subtitle punctuation and Persian diacritics/ZWNJ variants so
     * `"Don't"`, `"don't"` and `"don’t"` collapse to one key.
     */
    fun normalize(word: String): String = word
        .trim()
        .lowercase()
        .replace('\u2019', '\'')
        .replace('\u00A0', ' ')
        .replace(Regex("[\"“”‘’!?,.;:،。！？؛]"), "")
        .replace(Regex("\\s+"), " ")
        .replace("\u200c", " ")
        .trim()

    /** True for tokens that are punctuation-only and should never become a word entry. */
    fun isMarkable(word: String): Boolean = normalize(word).any { it.isLetterOrDigit() }

    /** Builds the FTS match string from a user query, quoting tokens so FTS syntax cannot leak in. */
    fun ftsQuery(query: String): String? {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"" + it.replace("\"", "") + "\"" + "*" }
    }
}

interface RecentVideoRepository {
    fun observeRecent(limit: Int = 40): Flow<List<RecentVideoEntity>>

    suspend fun touch(uri: String, title: String, durationMs: Long, positionMs: Long = 0L)

    suspend fun savePosition(uri: String, positionMs: Long)

    suspend fun remove(uri: String)

    suspend fun clearAll()

    suspend fun find(uri: String): RecentVideoEntity?
}

class RoomRecentVideoRepository(private val dao: RecentVideoDao) : RecentVideoRepository {
    override fun observeRecent(limit: Int): Flow<List<RecentVideoEntity>> = dao.observeRecent(limit)

    override suspend fun touch(uri: String, title: String, durationMs: Long, positionMs: Long) {
        val existing = dao.findByUri(uri)
        dao.upsert(
            (existing ?: RecentVideoEntity(uri = uri, title = title)).copy(
                uri = uri,
                title = title.ifBlank { existing?.title ?: uri.substringAfterLast('/') },
                durationMs = if (durationMs > 0) durationMs else existing?.durationMs ?: 0L,
                lastPositionMs = positionMs,
                lastOpenedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun savePosition(uri: String, positionMs: Long) = dao.savePosition(uri, positionMs)

    override suspend fun remove(uri: String) = dao.delete(uri)

    override suspend fun clearAll() = dao.clear()

    override suspend fun find(uri: String): RecentVideoEntity? = dao.findByUri(uri)
}

interface MyWordsRepository {
    fun observeAll(): Flow<List<MarkedWord>>

    fun search(query: String): Flow<List<MarkedWord>>

    suspend fun find(word: String): MarkedWord?

    suspend fun findByWords(words: List<String>): Map<String, MarkedWord>

    suspend fun statusWords(status: WordStatus): Set<String>

    suspend fun mark(word: MarkedWord): Long

    suspend fun remove(id: Long)

    suspend fun setStatus(id: Long, status: WordStatus)

    suspend fun setNote(id: Long, note: String?)

    suspend fun setTranslation(id: Long, translation: String?)

    fun observeCount(): Flow<Int>
}

class RoomMyWordsRepository(private val dao: MyWordDao) : MyWordsRepository {
    override fun observeAll(): Flow<List<MarkedWord>> = dao.observeAll().mapEntities()

    override fun search(query: String): Flow<List<MarkedWord>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return observeAll()
        val fts = WordKey.ftsQuery(normalized)
        return if (fts != null) dao.searchFts(fts, 200).mapEntities() else dao.searchLike(normalized, 200).mapEntities()
    }

    override suspend fun find(word: String): MarkedWord? =
        dao.findByWords(listOf(WordKey.lower(word))).firstOrNull()?.toDomain()

    override suspend fun statusWords(status: WordStatus): Set<String> = dao.wordsWithStatus(status.name).toSet()

    override suspend fun findByWords(words: List<String>): Map<String, MarkedWord> {
        if (words.isEmpty()) return emptyMap()
        val keys = words.map { WordKey.lower(it) }.distinct()
        return dao.findByWords(keys).associateBy({ it.wordLower }, { it.toDomain() })
    }

    override suspend fun mark(word: MarkedWord): Long {
        val existing = dao.findByWords(listOf(WordKey.lower(word.word))).firstOrNull()
        val entity = word.toEntity().copy(id = existing?.id ?: word.id, markedAt = existing?.markedAt ?: word.markedAt)
        dao.upsert(entity)
        return entity.id
    }

    override suspend fun remove(id: Long) = dao.deleteById(id)

    override suspend fun setStatus(id: Long, status: WordStatus) = dao.markStatus(id, status.name)

    override suspend fun setNote(id: Long, note: String?) = dao.updateNote(id, note)

    override suspend fun setTranslation(id: Long, translation: String?) = dao.updateTranslation(id, translation)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    private fun Flow<List<MyWordEntity>>.mapEntities(): Flow<List<MarkedWord>> =
        map { list -> list.map { it.toDomain() } }
}

