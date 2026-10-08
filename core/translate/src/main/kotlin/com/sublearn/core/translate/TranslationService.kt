package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.data.TranslationCacheDao
import com.sublearn.core.data.TranslationCacheEntity
import com.sublearn.core.data.TranslationKey
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.SubtitleNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches translated text so a re-watched video and a repeated tap are both instant, and so the
 * last translation stays readable even after the model is removed (GEN-7).
 */
interface TranslationCacheStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, source: String, translated: String, sourceLanguage: String, targetLanguage: String, provider: String)

    suspend fun trimTo(maxEntries: Int)

    suspend fun clear()
}

/** Test double and the fallback when persistence is turned off in Settings. */
class InMemoryTranslationCacheStore(private val hardMax: Int = 4_000) : TranslationCacheStore {
    data class Cached(val translated: String, var hits: Int = 0, val createdAt: Long = System.currentTimeMillis())

    private val entries = LinkedHashMap<String, Cached>()

    val size: Int get() = entries.size

    override suspend fun get(key: String): String? = entries[key]?.also { it.hits++ }?.translated

    override suspend fun put(
        key: String,
        source: String,
        translated: String,
        sourceLanguage: String,
        targetLanguage: String,
        provider: String,
    ) {
        entries[key] = Cached(translated)
        if (entries.size > hardMax) trimTo(hardMax)
    }

    /** Keeps the entries with the best hits-per-day-of-age, mirroring the SQL policy. */
    override suspend fun trimTo(maxEntries: Int) {
        if (entries.size <= maxEntries) return
        val now = System.currentTimeMillis()
        val drop = entries.entries
            .sortedBy { (_, value) -> (value.hits + 1).toDouble() / (1.0 + (now - value.createdAt) / 86_400_000.0) }
            .take(entries.size - maxEntries)
            .map { it.key }
        drop.forEach { entries.remove(it) }
    }

    override suspend fun clear() = entries.clear()
}

/** Room-backed cache used by the app. */
class RoomTranslationCacheStore(
    private val dao: TranslationCacheDao,
    private val maxEntries: Int = 4_000,
) : TranslationCacheStore {
    override suspend fun get(key: String): String? {
        val hit = dao.find(key) ?: return null
        dao.bump(key)
        return hit.translatedText
    }

    override suspend fun put(
        key: String,
        source: String,
        translated: String,
        sourceLanguage: String,
        targetLanguage: String,
        provider: String,
    ) {
        dao.put(
            TranslationCacheEntity(
                cacheKey = key,
                sourceText = source,
                translatedText = translated,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                provider = provider,
            ),
        )
    }

    override suspend fun trimTo(maxEntries: Int) {
        val count = dao.count()
        if (count > maxEntries) dao.evictLeastValuable(System.currentTimeMillis(), count - maxEntries)
    }

    override suspend fun clear() = dao.clear()
}

/** Request shaping; kept as a value so the service has no hidden dependencies. */
data class TranslationOptions(
    val sourceLanguage: String,
    val targetLanguage: String,
    val useCache: Boolean = true,
    val cacheMaxEntries: Int = 4_000,
    val translateWholeBlockFirst: Boolean = true,
)

/**
 * The only translation entry point the UI uses.
 *
 * Responsibilities: cache lookup, in-flight de-duplication (the subtitle layer and a popup asking
 * for the same line translate once), model-missing surfacing, and word/line/block granularity
 * (SUB-4).
 */
class TranslationService(
    private val provider: TranslationProvider,
    private val cache: TranslationCacheStore? = null,
    private val options: () -> TranslationOptions = { TranslationOptions("en", "fa") },
) {
    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<AppResult<String>>>()

    suspend fun translate(text: String): AppResult<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AppResult.success("")
        val opts = options()
        val key = TranslationKey.of(trimmed, opts.sourceLanguage, opts.targetLanguage, provider.id)
        if (opts.useCache) cache?.get(key)?.let { return AppResult.success(it) }

        val own = CompletableDeferred<AppResult<String>>()
        val existing = mutex.withLock {
            val previous = inFlight[key]
            if (previous == null) inFlight[key] = own
            previous
        }
        if (existing != null) return existing.await()

        val result = try {
            runWithChunking(trimmed, opts)
        } finally {
            mutex.withLock { if (inFlight[key] === own) inFlight.remove(key) }
        }
        result.getOrNull()?.let { translated ->
            if (opts.useCache) {
                cache?.put(key, trimmed, translated, opts.sourceLanguage, opts.targetLanguage, provider.id)
                cache?.trimTo(opts.cacheMaxEntries)
            }
        }
        own.complete(result)
        return result
    }

    private suspend fun runWithChunking(text: String, opts: TranslationOptions): AppResult<String> {
        val pieces = chunksFor(text)
        if (pieces.size == 1) return provider.translate(pieces[0], opts.sourceLanguage, opts.targetLanguage)
        val results = pieces.map { provider.translate(it, opts.sourceLanguage, opts.targetLanguage) }
        val firstError = results.firstNotNullOfOrNull { it.errorOrNull() }
        if (firstError != null) return AppResult.failure(firstError)
        return AppResult.success(results.mapNotNull { it.getOrNull() }.joinToString(" ").trim())
    }

    /** A block's full text: ML Kit sees the context, which is what makes Persian output readable. */
    suspend fun translateBlock(block: SubtitleBlock): AppResult<String> = translate(block.text)

    /**
     * Word-in-context translation. The bare word gives the short gloss, the sentence gives the
     * meaning in this line, because a one-word answer for "run" is useless (LRN-1).
     */
    suspend fun translateWord(word: String, contextText: String?): WordTranslation {
        val gloss = translate(word)
        val opts = options()
        val context = contextText?.takeIf { it.isNotBlank() && opts.translateWholeBlockFirst }
            ?.let { translate(contextLine(it, word)) }
        return WordTranslation(word, gloss, context)
    }

    /** Empties the on-device cache; the user's words stay in My Words. */
    suspend fun clearCache() {
        cache?.clear()
    }

    suspend fun isModelReady(): Boolean {
        val opts = options()
        return provider.isPairAvailable(opts.sourceLanguage, opts.targetLanguage)
    }

    suspend fun downloadModel(onProgress: (TranslationProgress) -> Unit): AppResult<Unit> {
        val opts = options()
        return provider.downloadModel(opts.sourceLanguage, opts.targetLanguage, onProgress)
    }

    /** The sentence around a word, so the contextual translation explains the word itself. */
    internal fun contextLine(text: String, word: String): String {
        val single = SubtitleNormalizer.removeLineBreaks(text)
        val sentence = single.split(Regex("(?<=[.!?。！？])\\s+")).firstOrNull { it.contains(word, ignoreCase = true) }
        return (sentence ?: single).trim()
    }

    /** Splits long input into provider-friendly pieces, never inside a word. */
    internal fun chunksFor(text: String, maxChars: Int = 900): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s+"))
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                if (current.isNotEmpty()) {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                out += SubtitleNormalizer.splitToMaxChars(sentence, maxChars)
                continue
            }
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
                out += current.toString().trim()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    }

    companion object {
        const val MODEL_MISSING_HINT = "download the on-device translation model in Settings → Translation"
    }
}

data class WordTranslation(
    val word: String,
    val gloss: AppResult<String>,
    val contextLineTranslation: AppResult<String>?,
) {
    val glossText: String? get() = gloss.getOrNull()
    val contextTranslation: String? get() = contextLineTranslation?.getOrNull()

    /** True when the failure is the fixable "no model on the device" case. */
    val needsModelDownload: Boolean
        get() = errorKind() == SubLearnError.Kind.ModelMissing

    val error: SubLearnError? get() = gloss.errorOrNull() ?: contextLineTranslation?.errorOrNull()

    private fun errorKind(): SubLearnError.Kind? = error?.kind
}
