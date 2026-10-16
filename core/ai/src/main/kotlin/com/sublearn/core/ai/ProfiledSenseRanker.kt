package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.nlp.PriorSenseRanker
import com.sublearn.core.nlp.SenseCandidate
import com.sublearn.core.nlp.SenseRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ranks senses by what the word means in this line, using a sentence encoder (`D-76`).
 *
 * The encoders in the catalog are English WordPiece models, so the comparison is English to
 * English: the subtitle line against each candidate's English gloss. A candidate with no English
 * side cannot be scored by an English encoder, and is therefore placed after the scored ones in
 * prior order rather than being given a number the model never produced.
 *
 * @property embedder the encoder to use, or null to stay on priors
 * @property fallback the lexical ranker, used when there is no model, no English text or a failure
 */
class ModelSenseRanker(
    private val embedder: TextEmbedder?,
    private val fallback: SenseRanker = PriorSenseRanker(),
) : SenseRanker {

    override suspend fun rank(
        lemmaKey: String,
        senses: List<SenseCandidate>,
        contextWindow: List<String>,
        targetPos: String?,
    ): List<SenseCandidate> {
        if (senses.size <= 1) return senses
        val active = embedder ?: return fallback.rank(lemmaKey, senses, contextWindow, targetPos)
        val context = contextWindow.joinToString(" ").trim()
        if (context.isBlank()) return fallback.rank(lemmaKey, senses, contextWindow, targetPos)

        val ordered = fallback.rank(lemmaKey, senses, contextWindow, targetPos)
        val scorable = ordered.filter { !it.glossEn.isNullOrBlank() }
        if (scorable.size < 2) return ordered

        return withContext(Dispatchers.Default) {
            val contextVector = runCatching { active.embed(context) }.getOrNull()
                ?: return@withContext ordered

            val scored = scorable.map { sense ->
                val gloss = sense.glossEn.orEmpty()
                val similarity = runCatching { EmbeddingMath.cosine(contextVector, active.embed(gloss)) }
                    .getOrDefault(0f)
                // The two bonuses are far below the spread the encoder produces, so they only
                // decide between candidates the model rates equally.
                val primary = if (sense.isPrimary) 0.05f else 0f
                val pos = if (targetPos != null && sense.pos == targetPos) 0.02f else 0f
                sense to (similarity + primary + pos)
            }
            val unscored = ordered.filter { it.glossEn.isNullOrBlank() }
            scored.sortedByDescending { it.second }.map { it.first } + unscored
        }
    }
}

/**
 * Asks the SMART profile's prompt model which of the candidate senses fits the line (`D-76`).
 *
 * The model is given numbered English meanings and asked for the number; anything it answers that
 * is not one of them — including a device where generation fails — falls back to [fallback], which
 * is the encoder ranking or the priors. A prompt model is allowed to be wrong about a word, but it
 * is never allowed to empty the card or reorder it at random.
 *
 * @property llm the prompt model, or null to stay on [fallback]
 * @property fallback the ranker to use when the model cannot answer
 * @property maxOptions the number of candidates offered. Small models lose the thread in long
 *   lists, and D-33 caps the LLM's choice at four.
 */
class LlmSenseRanker(
    private val llm: LlmRunner?,
    private val fallback: SenseRanker,
    private val maxOptions: Int = 4,
) : SenseRanker {

    override suspend fun rank(
        lemmaKey: String,
        senses: List<SenseCandidate>,
        contextWindow: List<String>,
        targetPos: String?,
    ): List<SenseCandidate> {
        if (senses.size <= 1) return senses
        val runner = llm ?: return fallback.rank(lemmaKey, senses, contextWindow, targetPos)
        val context = contextWindow.joinToString(" ").trim()
        if (context.isBlank()) return fallback.rank(lemmaKey, senses, contextWindow, targetPos)

        val ordered = fallback.rank(lemmaKey, senses, contextWindow, targetPos)
        val shortlist = ordered.take(maxOptions)
        val options = shortlist.map { it.glossEn?.takeIf { gloss -> gloss.isNotBlank() } ?: it.glossFa }
        if (options.size < 2) return ordered

        val answered = withContext(Dispatchers.Default) {
            runCatching { runner.choose(prompt(lemmaKey, context, options), options) }.getOrNull()
        }
        val choice = (answered as? AppResult.Success)?.value ?: return ordered
        val chosen = shortlist.getOrNull(choice) ?: return ordered
        return listOf(chosen) + ordered.filter { it.senseId != chosen.senseId }
    }

    /** The one prompt this ranker sends, kept here so the wording is reviewable next to its use. */
    internal fun prompt(lemmaKey: String, context: String, options: List<String>): String = buildString {
        appendLine("An English learner is reading this subtitle line:")
        appendLine(context)
        appendLine()
        appendLine("What does \"$lemmaKey\" mean here? Choose the best meaning:")
        // Zero-based on purpose: `LlmRunner.choose` answers with an index into the same list, so
        // numbering the prompt the same way means a digit or a repeated option both map straight
        // back to a candidate instead of needing an off-by-one correction here.
        options.forEachIndexed { index, option -> appendLine("$index. $option") }
        append("Answer with just the number.")
    }
}

/**
 * Picks the sense ranker a profile promises (`D-33`, `D-76`).
 *
 * `BASIC`, `LIGHT` and `CUSTOM` are defined as model-free, so they keep the prior ranker whatever
 * happens to be installed. The model profiles use the encoder when one is open; `SMART` puts the
 * prompt model on top of it. Nothing here *loads* anything — the caller passes in what
 * [ProfiledNlpEngine] already opened, which keeps the tier table in one place and testable.
 */
object ProfiledSenseRanker {

    fun forProfile(
        profile: ProfileTier,
        embedder: TextEmbedder? = null,
        llm: LlmRunner? = null,
    ): SenseRanker {
        val prior = PriorSenseRanker()
        return when (profile) {
            ProfileTier.BASIC, ProfileTier.LIGHT, ProfileTier.CUSTOM -> prior
            ProfileTier.BALANCED, ProfileTier.PRECISE, ProfileTier.ADVANCED -> ModelSenseRanker(embedder, prior)
            ProfileTier.SMART -> {
                val model = ModelSenseRanker(embedder, prior)
                if (llm != null) LlmSenseRanker(llm, model) else model
            }
        }
    }
}

/**
 * Keeps the engine a profile needs open, and hands out rankers bound to it.
 *
 * Opening an ONNX session costs hundreds of milliseconds and holds native memory, so it must not
 * happen per subtitle line or per tapped word. This class opens the profile's resident models once,
 * reuses them across calls, and reopens when the user changes profile. A profile whose models are
 * absent is *not* cached as a failure: the settings screen can install one at any moment, and the
 * next call is then allowed to succeed.
 *
 * @property store where the models live, and what is installed
 * @property profileProvider read on every call, so a profile change takes effect without a restart
 * @property installedIdsProvider defaults to what the store finds on disk
 */
class ProfiledNlpEngineCache(
    private val store: ModelStore,
    private val profileProvider: () -> ProfileTier,
    private val installedIdsProvider: () -> Set<String> = { store.installedIds() },
    private val embedderLoader: EmbedderLoader? = null,
    private val llmLoader: LlmLoader? = null,
) : AutoCloseable {

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var openProfile: ProfileTier? = null
    private var engine: ProfiledNlpEngine? = null
    private var report: ProfileRuntimeReport? = null

    /** What the last open attempt found, for the diagnostics line. */
    val lastReport: ProfileRuntimeReport? get() = synchronized(lock) { report }

    /**
     * The ranker for whichever profile is selected right now.
     *
     * Never throws: a profile that cannot be opened falls back to the prior ranker, because a
     * tapped word must still show its meanings.
     */
    fun senseRanker(): SenseRanker = synchronized(lock) {
        if (closed.get()) return PriorSenseRanker()

        val profile = profileProvider()
        val usable = report?.takeIf { !it.isEmpty }
        if (engine == null || openProfile != profile || usable == null) {
            open(profile)
        }
        val current = report
        if (current == null) return PriorSenseRanker()
        ProfiledSenseRanker.forProfile(profile, current.embedder, current.llm)
    }

    private fun open(profile: ProfileTier) {
        engine?.close()
        engine = null
        report = null
        openProfile = profile

        val opened = ProfiledNlpEngine(
            root = store.root,
            profile = profile,
            installedModelIds = runCatching { installedIdsProvider() }.getOrDefault(emptySet()),
            embedderLoader = embedderLoader,
            llmLoader = llmLoader,
        )
        when (val loaded = opened.load()) {
            is AppResult.Success -> {
                engine = opened
                report = loaded.value
            }

            is AppResult.Failure -> {
                opened.close()
                report = null
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            engine?.close()
            engine = null
            report = null
            openProfile = null
        }
    }
}
