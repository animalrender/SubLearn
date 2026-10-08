package com.sublearn.core.lexicon

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.data.WordKey
import com.sublearn.core.settings.CefrLevel

/**
 * Says how hard a word is for *this* user. LRN-2 needs an answer without a trained model, so the
 * shipped default is "words the user has not marked as known"; an imported frequency list can
 * replace it without touching a single caller.
 */
interface WordLevelProvider {
    val id: String

    /** Null means "no idea", which callers treat as "not above level" so popups stay quiet. */
    suspend fun level(word: String): CefrLevel?

    suspend fun isAboveLevel(word: String, userLevel: CefrLevel): Boolean {
        val level = level(word) ?: return false
        return level.ordinal > userLevel.ordinal
    }
}

/**
 * The default provider: My Words is the knowledge base. A word the user marked is known,
 * everything else inherits [unmarkedLevel] and therefore shows a popup when that level is above the
 * user's own level.
 */
class KnownWordsLevelProvider(
    private val knownWords: suspend () -> Set<String>,
    private val unmarkedLevel: CefrLevel = CefrLevel.B2,
) : WordLevelProvider {
    override val id = "known_words"

    override suspend fun level(word: String): CefrLevel? {
        val normalized = WordKey.normalize(word)
        if (normalized.isEmpty()) return null
        return if (normalized in knownWords()) CefrLevel.A1 else unmarkedLevel
    }

    override suspend fun isAboveLevel(word: String, userLevel: CefrLevel): Boolean {
        val normalized = WordKey.normalize(word)
        if (normalized.isEmpty()) return false
        return normalized !in knownWords() && unmarkedLevel.ordinal > userLevel.ordinal
    }
}

/** Manual level from Settings: the whole vocabulary is assumed to be exactly that level. */
class ManualLevelProvider(private val level: CefrLevel) : WordLevelProvider {
    override val id = "manual"

    override suspend fun level(word: String): CefrLevel? = if (level == CefrLevel.UNKNOWN) null else level
}

/**
 * A user-imported frequency/CEFR list. The file comes from the user because the licence audit could
 * not verify a permissively licensed CEFR word list (docs/AGENT_REQUESTS.md AR-002).
 */
class FrequencyListLevelProvider(
    private val entries: Map<String, CefrLevel>,
    private val fallback: CefrLevel? = null,
) : WordLevelProvider {
    override val id = "frequency_list"

    override suspend fun level(word: String): CefrLevel? = entries[WordKey.normalize(word)] ?: fallback

    fun size(): Int = entries.size
}

/** Asks each provider in turn and keeps the first real answer. */
class ChainedWordLevelProvider(private val providers: List<WordLevelProvider>) : WordLevelProvider {
    override val id: String = "chain:" + providers.joinToString("+") { it.id }

    override suspend fun level(word: String): CefrLevel? {
        for (provider in providers) {
            provider.level(word)?.let { return it }
        }
        return null
    }
}

/**
 * Parser for the importable list. The accepted shapes are deliberately boring so a user can produce
 * one from a spreadsheet: `word,level`, `word<TAB>level` or `word,frequency`, `#` for comments.
 */
object FrequencyListFormat {
    data class Parsed(
        val entries: Map<String, CefrLevel>,
        val warnings: List<String>,
        val rejected: Int,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    private val levels = listOf(CefrLevel.A1, CefrLevel.A2, CefrLevel.B1, CefrLevel.B2, CefrLevel.C1, CefrLevel.C2)
    private val levelNames = levels.map { it.name }.toSet()

    /** Buckets used when the file only carries frequencies (rank based). */
    fun levelFromRank(rank: Int, vocabularySize: Int): CefrLevel {
        if (vocabularySize <= 0) return CefrLevel.UNKNOWN
        val percentile = rank.toFloat() / vocabularySize.toFloat()
        val index = when {
            percentile <= 0.02f -> 0
            percentile <= 0.08f -> 1
            percentile <= 0.20f -> 2
            percentile <= 0.40f -> 3
            percentile <= 0.70f -> 4
            else -> 5
        }
        return levels[index]
    }

    /** Absolute frequency bands, for lists that publish counts rather than ranks. */
    fun levelFromFrequency(frequency: Long): CefrLevel {
        val index = when {
            frequency >= 200_000L -> 0
            frequency >= 60_000L -> 1
            frequency >= 15_000L -> 2
            frequency >= 3_000L -> 3
            frequency >= 400L -> 4
            else -> 5
        }
        return levels[index]
    }

    fun parse(text: String): Parsed {
        val entries = LinkedHashMap<String, CefrLevel>()
        val warnings = ArrayList<String>()
        var rejected = 0
        var rank = 0
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("word,", true)) return@forEachIndexed
            val fields = line.split('\t', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val word = fields.firstOrNull().orEmpty()
            val explicit = fields.lastOrNull()?.uppercase()?.takeIf { it in levelNames }
            val level: CefrLevel? = when {
                explicit != null -> CefrLevel.valueOf(explicit)
                fields.size >= 2 && fields[1].all { it.isDigit() } -> levelFromFrequency(fields[1].toLong())
                else -> null
            }
            if (word.isBlank() || level == null) {
                rank++
                rejected++
                if (warnings.size < 20) warnings += "line ${index + 1}: expected 'word,level' or 'word,frequency', got '${line.take(32)}'"
            } else {
                rank++
                entries[WordKey.normalize(word)] = level
            }
        }
        return Parsed(entries, warnings, rejected)
    }
}

/** Reads text the user picked through SAF; implemented in the app layer where a Context exists. */
interface TextResourceReader {
    /** Returns the display name and the decoded text of [uriKey]. */
    suspend fun readText(uriKey: String): AppResult<Pair<String, String>>

    companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}

/** Import + parse in one place so Settings and the player agree on what a valid list is. */
class FrequencyListImporter(private val reader: TextResourceReader) {
    suspend fun import(uriKey: String): AppResult<FrequencyListFormat.Parsed> {
        val read = reader.readText(uriKey)
        val (name, text) = read.getOrNull()
            ?: return AppResult.failure(read.errorOrNull() ?: SubLearnError(SubLearnError.Kind.NotFound, "file is not readable"))
        if (text.isBlank()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "$name is empty"))
        }
        val parsed = FrequencyListFormat.parse(text)
        if (parsed.isEmpty) {
            return AppResult.failure(
                SubLearnError(
                    SubLearnError.Kind.MalformedInput,
                    "no usable rows in $name; expected 'word,level' with a CEFR level (A1-C2)",
                ),
            )
        }
        return AppResult.success(parsed)
    }
}
