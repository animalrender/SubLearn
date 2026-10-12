package com.sublearn.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Domain shape for a marked word; keeps Room types out of the UI (GEN-6). */
@Serializable
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
    /** v2 fields: PHRASAL | IDIOM | COLLOCATION, or null for single words. */
    val phraseType: String? = null,
    val lemma: String? = null,
    val lexiconEntryId: Long? = null,
    val surfaceParts: String? = null,
    val sourceCueEndMs: Long = 0L,
    val sourceKind: String? = null,
)

@Serializable
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
    isPhrase = kind == MyWordEntity.KIND_MULTI_WORD || phraseType != null,
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
    phraseType = phraseType,
    lemma = lemma,
    lexiconEntryId = lexiconEntryId,
    surfaceParts = surfaceParts,
    sourceCueEndMs = sourceCueEndMs,
    sourceKind = sourceKind,
)

fun MarkedWord.toEntity(surface: String = MyWordEntity.SURFACE_SUBTITLE): MyWordEntity = MyWordEntity(
    id = id,
    word = word,
    wordLower = WordKey.lower(word),
    kind = if (isPhrase || phraseType != null) MyWordEntity.KIND_MULTI_WORD else MyWordEntity.KIND_WORD,
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
    phraseType = phraseType,
    lemma = lemma?.let { WordKey.lower(it) },
    lexiconEntryId = lexiconEntryId,
    surfaceParts = surfaceParts,
    sourceCueEndMs = sourceCueEndMs,
    sourceKind = sourceKind,
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
        .replace("\u200c", " ")
        .replace('\u064a', '\u06cc')
        .replace('\u0649', '\u06cc')
        .replace('\u0643', '\u06a9')
        .replace(Regex("[\\u064b-\\u065f\\u0670\\u0640]"), "")
        .replace(Regex("[\"“”‘’!?,.;:،。！？؛]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** True for tokens that are punctuation-only and should never become a word entry. */
    fun isMarkable(word: String): Boolean = normalize(word).any { it.isLetterOrDigit() }

    /**
     * Builds the FTS match string from a user query: every token becomes a quoted prefix phrase
     * (`"mak*"`), which is the FTS4 form that keeps `NOT`, `OR` and column syntax from leaking in.
     */
    fun ftsQuery(query: String): String? {
        val tokens = query.trim().split(Regex("\\s+")).map { it.replace("\"", "").replace("*", "") }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"" + it + "*\"" }
    }
}

enum class WordsExportKind { JSON, CSV, ANKI_TSV }

object MyWordsExporter {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(words: List<MarkedWord>, format: WordsExportKind): String = when (format) {
        WordsExportKind.JSON -> json.encodeToString(ListSerializer(MarkedWord.serializer()), words)
        WordsExportKind.CSV -> buildString {
            appendLine("word,lemma,phraseType,status,level,translation,contextText,note")
            words.forEach { w ->
                appendLine(
                    listOf(
                        escapeCsv(w.word),
                        escapeCsv(w.lemma ?: w.word),
                        escapeCsv(w.phraseType ?: if (w.isPhrase) "PHRASE" else "WORD"),
                        escapeCsv(w.status.name),
                        escapeCsv(w.level.orEmpty()),
                        escapeCsv(w.translation.orEmpty()),
                        escapeCsv(w.contextText.orEmpty()),
                        escapeCsv(w.note.orEmpty()),
                    ).joinToString(","),
                )
            }
        }
        WordsExportKind.ANKI_TSV -> buildString {
            words.forEach { w ->
                val front = w.word.replace('\t', ' ').replace('\n', ' ')
                val back = buildList {
                    w.translation?.takeIf { it.isNotBlank() }?.let { add(it) }
                    w.contextText?.takeIf { it.isNotBlank() }?.let { add("<i>$it</i>") }
                    w.note?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString("<br>").replace('\t', ' ').replace('\n', ' ')
                val tags = listOfNotNull(w.phraseType ?: if (w.isPhrase) "PHRASE" else "WORD", w.level, w.status.name)
                    .joinToString(" ")
                appendLine("$front\t$back\t$tags")
            }
        }
    }

    fun importText(text: String): List<MarkedWord> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return runCatching {
                json.decodeFromString(ListSerializer(MarkedWord.serializer()), trimmed)
            }.getOrDefault(emptyList())
        }
        val out = ArrayList<MarkedWord>()
        val now = System.currentTimeMillis()
        trimmed.lineSequence().forEachIndexed { idx, line ->
            val raw = line.trim()
            if (raw.isEmpty() || (idx == 0 && raw.startsWith("word,", ignoreCase = true))) return@forEachIndexed
            val parts = if ('\t' in raw) raw.split('\t') else parseCsvRow(raw)
            if (parts.isEmpty()) return@forEachIndexed
            val word = parts[0].trim()
            if (!WordKey.isMarkable(word)) return@forEachIndexed
            if ('\t' in raw) {
                val meaning = parts.getOrNull(1)?.replace("<br>", " — ")?.replace(Regex("<[^>]+>"), "")?.trim()
                out += MarkedWord(
                    id = 0L,
                    word = word,
                    isPhrase = ' ' in word,
                    translation = meaning,
                    contextText = null,
                    contextTranslation = null,
                    sourceTitle = null,
                    sourceStartMs = 0L,
                    status = WordStatus.LEARNING,
                    level = null,
                    note = null,
                    markedAt = now,
                    reviewCount = 0,
                    lemma = WordKey.lower(word),
                )
            } else {
                val lemma = parts.getOrNull(1)?.trim()?.ifBlank { null }
                val pType = parts.getOrNull(2)?.trim()?.takeIf { it in setOf("PHRASAL", "IDIOM", "COLLOCATION") }
                val status = WordStatus.fromKey(parts.getOrNull(3)?.trim())
                val level = parts.getOrNull(4)?.trim()?.ifBlank { null }
                val trans = parts.getOrNull(5)?.trim()?.ifBlank { null }
                val ctx = parts.getOrNull(6)?.trim()?.ifBlank { null }
                val note = parts.getOrNull(7)?.trim()?.ifBlank { null }
                out += MarkedWord(
                    id = 0L,
                    word = word,
                    isPhrase = pType != null || ' ' in word,
                    translation = trans,
                    contextText = ctx,
                    contextTranslation = null,
                    sourceTitle = null,
                    sourceStartMs = 0L,
                    status = status,
                    level = level,
                    note = note,
                    markedAt = now,
                    reviewCount = 0,
                    phraseType = pType,
                    lemma = lemma ?: WordKey.lower(word),
                )
            }
        }
        return out
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        if (',' !in clean && '"' !in clean) return clean
        val escaped = clean.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun parseCsvRow(line: String): List<String> {
        val res = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    res.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        res.add(sb.toString())
        return res
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

    suspend fun getAll(): List<MarkedWord> = emptyList()

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

    override suspend fun getAll(): List<MarkedWord> = dao.getAll().map { it.toDomain() }

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
        val result = HashMap<String, MarkedWord>()
        for (chunk in keys.chunked(400)) {
            val entities = dao.findByWords(chunk)
            for (entity in entities) {
                val domain = entity.toDomain()
                result[entity.wordLower] = domain
                entity.lemma?.let { lem -> result.putIfAbsent(lem, domain) }
            }
        }
        return result
    }

    override suspend fun mark(word: MarkedWord): Long {
        val existing = dao.findByWords(listOf(WordKey.lower(word.word))).firstOrNull { it.wordLower == WordKey.lower(word.word) }
        val entity = word.toEntity().copy(id = existing?.id ?: word.id, markedAt = existing?.markedAt ?: word.markedAt)
        dao.upsert(entity)
        return if (entity.id != 0L) entity.id else (dao.findByWords(listOf(entity.wordLower)).firstOrNull()?.id ?: 1L)
    }

    override suspend fun remove(id: Long) = dao.deleteById(id)

    override suspend fun setStatus(id: Long, status: WordStatus) = dao.markStatus(id, status.name)

    override suspend fun setNote(id: Long, note: String?) = dao.updateNote(id, note)

    override suspend fun setTranslation(id: Long, translation: String?) = dao.updateTranslation(id, translation)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    private fun Flow<List<MyWordEntity>>.mapEntities(): Flow<List<MarkedWord>> =
        map { list -> list.map { it.toDomain() } }
}

interface InstalledPackRepository {
    fun observeAll(): Flow<List<InstalledPackEntity>>

    suspend fun getAll(): List<InstalledPackEntity>

    suspend fun findActiveByKind(kind: String): InstalledPackEntity?

    suspend fun findById(packId: String): InstalledPackEntity?

    suspend fun register(entity: InstalledPackEntity)

    suspend fun setEnabled(packId: String, enabled: Boolean)

    suspend fun remove(packId: String)
}

class RoomInstalledPackRepository(private val dao: InstalledPackDao) : InstalledPackRepository {
    override fun observeAll(): Flow<List<InstalledPackEntity>> = dao.observeAll()

    override suspend fun getAll(): List<InstalledPackEntity> = dao.getAll()

    override suspend fun findActiveByKind(kind: String): InstalledPackEntity? = dao.findActiveByKind(kind)

    override suspend fun findById(packId: String): InstalledPackEntity? = dao.findById(packId)

    override suspend fun register(entity: InstalledPackEntity) = dao.upsert(entity)

    override suspend fun setEnabled(packId: String, enabled: Boolean) = dao.setEnabled(packId, enabled)

    override suspend fun remove(packId: String) = dao.deleteById(packId)
}
