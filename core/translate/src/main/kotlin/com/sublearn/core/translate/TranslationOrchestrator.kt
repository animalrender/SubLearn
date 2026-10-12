package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.data.TranslationKey
import com.sublearn.core.data.WordKey
import com.sublearn.core.settings.TranslationOrchestrationMode
import com.sublearn.core.settings.TranslationSettings
import com.sublearn.core.settings.TranslationSourceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

data class TranslationEngineHit(
    val text: String,
    val posGlosses: List<Pair<String?, List<String>>> = emptyList(),
    val phonetic: String? = null,
    val cefr: String? = null,
    val lemma: String? = null,
    val lexiconEntryId: Long? = null,
    val examples: List<Pair<String, String>> = emptyList(),
    val sourceKind: TranslationSourceKind,
    val latencyMs: Long = 0L,
    val fromCache: Boolean = false,
)

interface TranslationEngine {
    val id: String
    val kind: TranslationSourceKind
    val isOffline: Boolean

    suspend fun isAvailable(sourceLanguage: String, targetLanguage: String): Boolean

    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AppResult<TranslationEngineHit>
}

data class PackGlossaryResult(
    val headword: String,
    val lemma: String? = null,
    val cefr: String? = null,
    val phonetic: String? = null,
    val primaryGloss: String,
    val posGlosses: List<Pair<String?, List<String>>> = emptyList(),
    val examples: List<Pair<String, String>> = emptyList(),
    val lexiconEntryId: Long? = null,
)

class MlKitTranslationEngine(
    private val provider: TranslationProvider,
) : TranslationEngine {
    override val id: String = "ML_KIT"
    override val kind: TranslationSourceKind = TranslationSourceKind.ML_KIT
    override val isOffline: Boolean = true

    override suspend fun isAvailable(sourceLanguage: String, targetLanguage: String): Boolean =
        provider.isPairAvailable(sourceLanguage, targetLanguage)

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AppResult<TranslationEngineHit> {
        val start = System.currentTimeMillis()
        val res = provider.translate(text, sourceLanguage, targetLanguage)
        val out = res.getOrNull()?.trim().orEmpty()
        if (out.isEmpty()) {
            return AppResult.failure(res.errorOrNull() ?: SubLearnError(SubLearnError.Kind.NotFound, "No ML Kit translation"))
        }
        return AppResult.success(
            TranslationEngineHit(
                text = out,
                sourceKind = TranslationSourceKind.ML_KIT,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L),
            ),
        )
    }
}

class PackTranslationEngine(
    private val isPackReady: () -> Boolean = { true },
    private val lookupFn: suspend (String) -> PackGlossaryResult?,
) : TranslationEngine {
    override val id: String = "PACK"
    override val kind: TranslationSourceKind = TranslationSourceKind.PACK
    override val isOffline: Boolean = true

    override suspend fun isAvailable(sourceLanguage: String, targetLanguage: String): Boolean = isPackReady()

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AppResult<TranslationEngineHit> {
        if (!isPackReady()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.ModelMissing, "No v2 pack installed"))
        }
        val start = System.currentTimeMillis()
        val hit = lookupFn(text.trim())
            ?: return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "Not found in v2 pack"))
        if (hit.primaryGloss.isBlank()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "Empty gloss in v2 pack"))
        }
        return AppResult.success(
            TranslationEngineHit(
                text = hit.primaryGloss,
                posGlosses = hit.posGlosses,
                phonetic = hit.phonetic,
                cefr = hit.cefr,
                lemma = hit.lemma,
                lexiconEntryId = hit.lexiconEntryId,
                examples = hit.examples,
                sourceKind = TranslationSourceKind.PACK,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L),
            ),
        )
    }
}

class LegacyV1TranslationEngine(
    private val isLegacyReady: () -> Boolean = { false },
    private val lookupFn: suspend (String) -> PackGlossaryResult?,
) : TranslationEngine {
    override val id: String = "LEGACY_V1"
    override val kind: TranslationSourceKind = TranslationSourceKind.LEGACY_V1
    override val isOffline: Boolean = true

    override suspend fun isAvailable(sourceLanguage: String, targetLanguage: String): Boolean = isLegacyReady()

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AppResult<TranslationEngineHit> {
        if (!isLegacyReady()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.ModelMissing, "No legacy v1 pack installed"))
        }
        val start = System.currentTimeMillis()
        val hit = lookupFn(text.trim())
            ?: return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "Not found in legacy v1 pack"))
        if (hit.primaryGloss.isBlank()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "Empty gloss in legacy v1 pack"))
        }
        return AppResult.success(
            TranslationEngineHit(
                text = hit.primaryGloss,
                posGlosses = hit.posGlosses,
                phonetic = hit.phonetic,
                cefr = hit.cefr,
                lemma = hit.lemma,
                lexiconEntryId = hit.lexiconEntryId,
                examples = hit.examples,
                sourceKind = TranslationSourceKind.LEGACY_V1,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L),
            ),
        )
    }
}

data class OrchestratedTranslation(
    val query: String,
    val primaryTranslation: String?,
    val mergedMeanings: List<String> = emptyList(),
    val posGlosses: List<Pair<String?, List<String>>> = emptyList(),
    val phonetic: String? = null,
    val cefr: String? = null,
    val lemma: String? = null,
    val lexiconEntryId: Long? = null,
    val examples: List<Pair<String, String>> = emptyList(),
    val sourcesUsed: List<TranslationSourceKind> = emptyList(),
    val contextTranslation: String? = null,
    val isPendingOnline: Boolean = false,
    val error: SubLearnError? = null,
)

/**
 * Multi-source translation orchestrator (`D-58`, `D-59`, `D-62`).
 *
 * - Runs enabled offline sources (`PACK`, `ML_KIT`, `LEGACY_V1`) first with an immediate budget.
 * - Supports `PRIORITY_CHAIN` (stops at first non-empty source) and `MERGE_ALL` (queries all
 *   enabled sources and deduplicates meanings with source provenance chips).
 * - When `ONLINE_GOOGLE` is enabled and eligible, [translateFlow] emits the immediate offline
 *   result first and then emits the updated result once the online engine completes.
 */
class TranslationOrchestrator(
    private val engines: Map<TranslationSourceKind, TranslationEngine>,
    private val cache: TranslationCacheStore? = null,
    private val settingsProvider: () -> TranslationSettings = { TranslationSettings() },
    private val offlineBudgetMs: Long = 250L,
) {

    suspend fun translateImmediate(
        query: String,
        contextText: String? = null,
        sourceLanguage: String = "en",
        targetLanguage: String = "fa",
    ): OrchestratedTranslation {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return OrchestratedTranslation(query = query, primaryTranslation = "")
        }

        val settings = settingsProvider()
        val orderedKinds = settings.activeOrderedSources()
        val offlineKinds = orderedKinds.filter { engines[it]?.isOffline == true }
        val onlineEnabled = TranslationSourceKind.ONLINE_GOOGLE in orderedKinds &&
            !settings.offlineOnly &&
            settings.onlinePrivacyAccepted

        val hits = ArrayList<TranslationEngineHit>()
        var lastError: SubLearnError? = null

        for (kind in offlineKinds) {
            val engine = engines[kind] ?: continue
            val hitResult = queryEngineWithCache(engine, trimmed, sourceLanguage, targetLanguage, settings, offlineBudgetMs)
            val hit = hitResult.getOrNull()
            if (hit != null && hit.text.isNotBlank()) {
                hits += hit
                if (settings.mode == TranslationOrchestrationMode.PRIORITY_CHAIN) {
                    break
                }
            } else {
                hitResult.errorOrNull()?.let { lastError = it }
            }
        }

        val shouldQueryOnline = onlineEnabled && (
            settings.mode == TranslationOrchestrationMode.MERGE_ALL || hits.isEmpty()
            )

        val contextTrans = resolveContextTranslation(contextText, trimmed, sourceLanguage, targetLanguage, settings)

        return mergeHits(
            query = trimmed,
            hits = hits,
            contextTranslation = contextTrans,
            isPendingOnline = shouldQueryOnline,
            lastError = if (hits.isEmpty() && !shouldQueryOnline) lastError else null,
        )
    }

    fun translateFlow(
        query: String,
        contextText: String? = null,
        sourceLanguage: String = "en",
        targetLanguage: String = "fa",
    ): Flow<OrchestratedTranslation> = flow {
        val initial = translateImmediate(query, contextText, sourceLanguage, targetLanguage)
        emit(initial)

        if (!initial.isPendingOnline) return@flow

        val settings = settingsProvider()
        val onlineEngine = engines[TranslationSourceKind.ONLINE_GOOGLE] ?: return@flow
        val onlineRes = queryEngineWithCache(
            engine = onlineEngine,
            text = query.trim(),
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            settings = settings,
            budgetMs = settings.onlineTimeoutMs,
        )

        val onlineHit = onlineRes.getOrNull()
        if (onlineHit != null && onlineHit.text.isNotBlank()) {
            val combinedMeanings = deduplicateMeanings(initial.mergedMeanings + splitMeanings(onlineHit.text))
            val combinedPos = mergePosGlosses(initial.posGlosses, onlineHit.posGlosses)
            val combinedSources = (initial.sourcesUsed + TranslationSourceKind.ONLINE_GOOGLE).distinct()
            emit(
                initial.copy(
                    primaryTranslation = combinedMeanings.joinToString("، ").ifEmpty { onlineHit.text },
                    mergedMeanings = combinedMeanings,
                    posGlosses = combinedPos,
                    phonetic = initial.phonetic ?: onlineHit.phonetic,
                    sourcesUsed = combinedSources,
                    isPendingOnline = false,
                    error = null,
                ),
            )
        } else {
            emit(
                initial.copy(
                    isPendingOnline = false,
                    error = if (initial.primaryTranslation.isNullOrBlank()) onlineRes.errorOrNull() else null,
                ),
            )
        }
    }

    private suspend fun queryEngineWithCache(
        engine: TranslationEngine,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        settings: TranslationSettings,
        budgetMs: Long,
    ): AppResult<TranslationEngineHit> {
        val cacheKey = TranslationKey.of(text, sourceLanguage, targetLanguage, engine.id)
        if (settings.cacheEnabled) {
            val cached = cache?.get(cacheKey)
            if (!cached.isNullOrBlank()) {
                return AppResult.success(
                    TranslationEngineHit(
                        text = cached,
                        sourceKind = engine.kind,
                        fromCache = true,
                    ),
                )
            }
        }

        val result = if (engine.isOffline) {
            withTimeoutOrNull(budgetMs) {
                engine.translate(text, sourceLanguage, targetLanguage)
            } ?: AppResult.failure(SubLearnError(SubLearnError.Kind.Timeout, "${engine.id} exceeded ${budgetMs}ms budget"))
        } else {
            engine.translate(text, sourceLanguage, targetLanguage)
        }

        result.getOrNull()?.let { hit ->
            if (settings.cacheEnabled && hit.text.isNotBlank()) {
                cache?.put(cacheKey, text, hit.text, sourceLanguage, targetLanguage, engine.id)
                cache?.trimTo(settings.cacheMaxEntries)
            }
        }
        return result
    }

    private suspend fun resolveContextTranslation(
        contextText: String?,
        word: String,
        sourceLanguage: String,
        targetLanguage: String,
        settings: TranslationSettings,
    ): String? {
        if (contextText.isNullOrBlank() || !settings.translateWholeBlockFirst) return null
        val mlKit = engines[TranslationSourceKind.ML_KIT] ?: return null
        if (TranslationSourceKind.ML_KIT !in settings.activeOrderedSources()) return null
        val res = queryEngineWithCache(mlKit, contextText.trim(), sourceLanguage, targetLanguage, settings, offlineBudgetMs)
        return res.getOrNull()?.text
    }

    companion object {
        private val MEANING_SPLIT_REGEX = Regex("[،,;؛/]+")

        fun splitMeanings(raw: String): List<String> =
            raw.split(MEANING_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        fun deduplicateMeanings(items: List<String>): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            for (item in items) {
                val norm = WordKey.normalize(item)
                if (norm.isNotEmpty() && seen.add(norm)) {
                    out += item.trim()
                }
            }
            return out
        }

        fun mergePosGlosses(
            first: List<Pair<String?, List<String>>>,
            second: List<Pair<String?, List<String>>>,
        ): List<Pair<String?, List<String>>> {
            if (second.isEmpty()) return first
            if (first.isEmpty()) return second
            val map = LinkedHashMap<String?, MutableList<String>>()
            for ((pos, terms) in first + second) {
                map.getOrPut(pos) { ArrayList() }.addAll(terms)
            }
            return map.map { (pos, terms) -> pos to deduplicateMeanings(terms) }
        }

        fun mergeHits(
            query: String,
            hits: List<TranslationEngineHit>,
            contextTranslation: String?,
            isPendingOnline: Boolean,
            lastError: SubLearnError?,
        ): OrchestratedTranslation {
            if (hits.isEmpty()) {
                return OrchestratedTranslation(
                    query = query,
                    primaryTranslation = null,
                    contextTranslation = contextTranslation,
                    isPendingOnline = isPendingOnline,
                    error = lastError,
                )
            }

            val allMeanings = deduplicateMeanings(hits.flatMap { splitMeanings(it.text) })
            var mergedPos: List<Pair<String?, List<String>>> = emptyList()
            for (h in hits) {
                mergedPos = mergePosGlosses(mergedPos, h.posGlosses)
            }

            return OrchestratedTranslation(
                query = query,
                primaryTranslation = allMeanings.joinToString("، ").ifEmpty { hits.first().text },
                mergedMeanings = allMeanings,
                posGlosses = mergedPos,
                phonetic = hits.firstNotNullOfOrNull { it.phonetic },
                cefr = hits.firstNotNullOfOrNull { it.cefr },
                lemma = hits.firstNotNullOfOrNull { it.lemma },
                lexiconEntryId = hits.firstNotNullOfOrNull { it.lexiconEntryId },
                examples = hits.flatMap { it.examples }.distinct().take(4),
                sourcesUsed = hits.map { it.sourceKind }.distinct(),
                contextTranslation = contextTranslation,
                isPendingOnline = isPendingOnline,
                error = null,
            )
        }
    }
}
