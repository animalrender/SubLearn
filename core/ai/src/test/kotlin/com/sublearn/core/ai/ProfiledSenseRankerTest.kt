package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.nlp.PriorSenseRanker
import com.sublearn.core.nlp.SenseCandidate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profile-to-ranker table, and the two model-backed rankers behind it (`D-76`).
 *
 * The embedder and the prompt model are fakes, so what is pinned here is the *decision*: which
 * profile gets which ranker, what happens when a model is missing, and that a model that fails
 * mid-call leaves the lexical order intact instead of emptying the card.
 */
class ProfiledSenseRankerTest {

    @After
    fun tearDown() {
        NlpRuntimeRegistry.reset()
    }

    // --- the tier table -------------------------------------------------------------------

    @Test
    fun `model-free profiles keep the prior ranker`() {
        assertTrue(ProfiledSenseRanker.forProfile(ProfileTier.BASIC) is PriorSenseRanker)
        assertTrue(ProfiledSenseRanker.forProfile(ProfileTier.LIGHT) is PriorSenseRanker)
        assertTrue(ProfiledSenseRanker.forProfile(ProfileTier.CUSTOM) is PriorSenseRanker)
        // A model profile with nothing open is still lexical rather than broken.
        assertTrue(ProfiledSenseRanker.forProfile(ProfileTier.PRECISE) is ModelSenseRanker)
    }

    @Test
    fun `smart uses the prompt model when one is open`() {
        val runner = FakeRunner(AppResult.Success("1"))
        val ranker = ProfiledSenseRanker.forProfile(ProfileTier.SMART, embedder = null, llm = runner)
        assertTrue(ranker is LlmSenseRanker)
        // No prompt model: it degrades to the encoder ranking, still not to a crash.
        assertTrue(ProfiledSenseRanker.forProfile(ProfileTier.SMART, embedder = null, llm = null) is ModelSenseRanker)
    }

    // --- embedding ranking ----------------------------------------------------------------

    @Test
    fun `the encoder picks the sense whose english gloss fits the line`() = runTest {
        // The stub maps each text to a unit vector: the context points at the "river bank" sense
        // and away from the "money" one.
        val embedder = StubEmbedder(
            mapOf(
                "he sat on the river bank" to floatArrayOf(1f, 0f),
                "the side of a river" to floatArrayOf(1f, 0f),
                "a place that keeps money" to floatArrayOf(-1f, 0f),
            ),
        )
        val senses = listOf(
            sense(id = 1, glossEn = "a place that keeps money", primary = true),
            sense(id = 2, glossEn = "the side of a river"),
        )

        val ranked = ModelSenseRanker(embedder).rank(
            lemmaKey = "bank",
            senses = senses,
            contextWindow = listOf("he", "sat", "on", "the", "river", "bank"),
            targetPos = "NOUN",
        )

        // The primary flag is worth 0.05, while the encoder's swing here is 2.0, so meaning wins.
        assertEquals(listOf(2L, 1L), ranked.map { it.senseId })
    }

    @Test
    fun `a candidate without an english gloss is not scored by an english encoder`() = runTest {
        val embedder = StubEmbedder(mapOf("a line" to floatArrayOf(1f, 0f), "match" to floatArrayOf(1f, 0f)))
        val senses = listOf(
            sense(id = 1, glossEn = "match"),
            sense(id = 2, glossEn = null),
            sense(id = 3, glossEn = "unrelated"),
        )

        val ranked = ModelSenseRanker(embedder).rank("x", senses, listOf("a", "line"), null)

        assertEquals(1L, ranked.first().senseId)
        assertEquals("the unscored candidate keeps its prior position at the end", 2L, ranked.last().senseId)
    }

    @Test
    fun `a broken embedder leaves the lexical order alone`() = runTest {
        val senses = listOf(
            sense(id = 1, glossEn = "first", primary = true),
            sense(id = 2, glossEn = "second"),
        )
        val ranked = ModelSenseRanker(ThrowingEmbedder()).rank("x", senses, listOf("a", "line"), null)
        assertEquals(listOf(1L, 2L), ranked.map { it.senseId })
    }

    @Test
    fun `a single sense is returned untouched`() = runTest {
        val one = listOf(sense(id = 7, glossEn = "only"))
        assertEquals(one, ModelSenseRanker(ThrowingEmbedder()).rank("x", one, listOf("a"), null))
    }

    // --- llm ranking ----------------------------------------------------------------------

    @Test
    fun `the prompt model's choice moves to the front`() = runTest {
        // The prior order is money first (it is the primary sense), so the model has to
        // actively choose the river sense for the order to change.
        val runner = FakeRunner(AppResult.Success("1"))
        val senses = listOf(
            sense(id = 1, glossEn = "money", primary = true),
            sense(id = 2, glossEn = "river"),
        )

        val ranked = LlmSenseRanker(runner, ModelSenseRanker(null)).rank("bank", senses, listOf("river", "bank"), null)

        assertEquals(2L, ranked.first().senseId)
        assertEquals(2, ranked.size)
        assertTrue(runner.lastPrompt.contains("0. money"))
        assertTrue(runner.lastPrompt.contains("1. river"))
    }

    @Test
    fun `an unusable answer falls back to the lexical ranking`() = runTest {
        val senses = listOf(
            sense(id = 1, glossEn = "money", primary = true),
            sense(id = 2, glossEn = "river"),
        )
        val refused = SubLearnError(SubLearnError.Kind.ModelFailure, "the model refused")
        val failed = LlmSenseRanker(FakeRunner(AppResult.Failure(refused)), ModelSenseRanker(null))
        assertEquals(listOf(1L, 2L), failed.rank("bank", senses, listOf("river"), null).map { it.senseId })

        val outOfRange = LlmSenseRanker(FakeRunner(AppResult.Success("9")), ModelSenseRanker(null))
        assertEquals(listOf(1L, 2L), outOfRange.rank("bank", senses, listOf("river"), null).map { it.senseId })
    }

    @Test
    fun `the llm's shortlist is capped`() = runTest {
        val runner = FakeRunner(AppResult.Success("no number here"))
        val senses = (1L..8L).map { sense(id = it, glossEn = "gloss $it") }
        LlmSenseRanker(runner, ModelSenseRanker(null)).rank("w", senses, listOf("context"), null)
        // Three options in the prompt (0, 1, 2), then "Answer with just the number." on the last line.
        assertEquals(4, runner.lastPrompt.lines().count { it.startsWith("0.") || Regex("^[1-3]\\. ").containsMatchIn(it) })
    }

    // --- the engine cache -----------------------------------------------------------------

    @Test
    fun `the cache opens the profile's model once and reuses it`() = runTest {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val loader = CountingLoader()
        val cache = cacheFor(ProfileTier.PRECISE, installed = setOf("emb-minilm-l6-int8"), loader = loader)

        val first = cache.senseRanker()
        val second = cache.senseRanker()

        assertTrue(first is ModelSenseRanker)
        assertTrue(second is ModelSenseRanker)
        assertEquals("the ONNX session is opened once for the same profile", 1, loader.loads)
        assertTrue(cache.lastReport?.meetsProfile == true)
        cache.close()
        assertEquals(0, loader.open)
    }

    @Test
    fun `the cache reopens when the profile changes or a model appears later`() = runTest {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val loader = CountingLoader()
        var installed = emptySet<String>()
        var profile = ProfileTier.PRECISE
        val cache = ProfiledNlpEngineCache(
            store = ModelStore(File("/nonexistent-models")),
            profileProvider = { profile },
            installedIdsProvider = { installed },
            embedderLoader = loader,
        )

        // Nothing installed yet: the prior ranker, and no session held open.
        assertTrue(cache.senseRanker() is PriorSenseRanker)
        assertEquals(0, loader.loads)

        // The user installs the model: the next call is allowed to succeed — a cached failure
        // would have left the profile unusable until the app restarted.
        installed = setOf("emb-minilm-l6-int8")
        assertTrue(cache.senseRanker() is ModelSenseRanker)
        assertEquals(1, loader.loads)

        // A profile change must not keep the old profile's session resident. ADVANCED needs the
        // word-sense encoder, which is a different file.
        installed = setOf("emb-minilm-l6-int8", "wsd-distilbert-int8")
        profile = ProfileTier.ADVANCED
        cache.senseRanker()
        assertEquals("ADVANCED opens its own encoder", 2, loader.loads)
        assertEquals("but only one session is resident", 1, loader.open)
        cache.close()
    }

    @Test
    fun `invalidating closes the open model and the next call reopens it`() = runTest {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val loader = CountingLoader()
        val cache = cacheFor(ProfileTier.PRECISE, setOf("emb-minilm-l6-int8"), loader)
        cache.senseRanker()
        assertEquals(1, loader.open)

        cache.invalidate()

        assertEquals("the model must be released, not merely forgotten", 0, loader.open)
        assertNull(cache.lastReport)
        cache.senseRanker()
        assertEquals(2, loader.loads)
        assertEquals(1, loader.open)
        cache.close()
    }

    @Test
    fun `closing the cache is idempotent`() = runTest {
        NlpRuntimeRegistry.register(NlpModelRuntime.ONNX)
        val loader = CountingLoader()
        val cache = cacheFor(ProfileTier.PRECISE, setOf("emb-minilm-l6-int8"), loader)
        cache.senseRanker()
        cache.close()
        cache.close()
        assertTrue(cache.senseRanker() is PriorSenseRanker)
    }

    // --- fakes ----------------------------------------------------------------------------

    private fun cacheFor(
        profile: ProfileTier,
        installed: Set<String>,
        loader: EmbedderLoader,
    ): ProfiledNlpEngineCache = ProfiledNlpEngineCache(
        store = ModelStore(File("/nonexistent-models")),
        profileProvider = { profile },
        installedIdsProvider = { installed },
        embedderLoader = loader,
    )

    private fun sense(id: Long, glossEn: String?, primary: Boolean = false) = SenseCandidate(
        senseId = id,
        glossFa = "معنی $id",
        glossEn = glossEn,
        pos = "NOUN",
        domain = null,
        register = null,
        isPrimary = primary,
    )

    private class StubEmbedder(private val vectors: Map<String, FloatArray>) : TextEmbedder {
        override val dimensions: Int = 2
        override val runtimeName: String = "stub"

        override fun embed(text: String): FloatArray =
            vectors[text.lowercase()] ?: error("no vector for \"$text\"")

        override fun close() = Unit
    }

    private class ThrowingEmbedder : TextEmbedder {
        override val dimensions: Int = 2
        override val runtimeName: String = "broken"
        override fun embed(text: String): FloatArray = throw IllegalStateException("no session")
        override fun close() = Unit
    }

    private class FakeRunner(private val answer: AppResult<String>) : LlmRunner {
        var lastPrompt: String = ""
            private set

        override val runtimeName: String = "fake-llm"
        override val modelId: String = "fake"

        override fun generate(prompt: String): AppResult<String> {
            lastPrompt = prompt
            return answer
        }

        override fun choose(prompt: String, options: List<String>): AppResult<Int?> {
            lastPrompt = prompt
            val text = when (answer) {
                is AppResult.Success -> answer.value
                is AppResult.Failure -> return AppResult.Failure(answer.error)
            }
            val normalised = text.lowercase()
            val index = options.indices.firstOrNull { normalised.contains(it.toString()) }
                ?: options.indices.firstOrNull { normalised.contains(options[it].lowercase()) }
            return AppResult.Success(index)
        }

        override fun close() = Unit
    }

    /** A loader that hands out a stub embedder and counts how many sessions are open. */
    private class CountingLoader : EmbedderLoader {
        var loads = 0
            private set
        var open = 0
            private set

        override fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<TextEmbedder> {
            loads++
            open++
            val embedder = object : TextEmbedder {
                override val dimensions: Int = 2
                override val runtimeName: String = "counting"
                override fun embed(text: String): FloatArray = floatArrayOf(1f, 0f)
                override fun close() {
                    open--
                }
            }
            return AppResult.Success(embedder)
        }
    }
}
