package com.sublearn.core.models

import com.sublearn.core.settings.AccuracyProfileToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard is what stops a profile from stuttering playback, so its budgets, its downgrade rule
 * and its two fallback paths are pinned here (§5.1, D-52).
 */
class ModelRuntimeGuardTest {

    @Test
    fun `budgets rise with the profile`() {
        assertEquals(32, ModelRuntimeGuard.minHeapMbFor(AccuracyProfileToken.BASIC))
        assertEquals(1024, ModelRuntimeGuard.minHeapMbFor(AccuracyProfileToken.SMART_EXPERIMENTAL))
        assertTrue(
            ModelRuntimeGuard.minHeapMbFor(AccuracyProfileToken.PRECISE) <
                ModelRuntimeGuard.minHeapMbFor(AccuracyProfileToken.ADVANCED),
        )
    }

    @Test
    fun `timeouts rise with the profile`() {
        assertEquals(50L, ModelRuntimeGuard.timeoutMsFor(AccuracyProfileToken.BASIC))
        assertEquals(150L, ModelRuntimeGuard.timeoutMsFor(AccuracyProfileToken.ADVANCED))
        assertEquals(1_500L, ModelRuntimeGuard.timeoutMsFor(AccuracyProfileToken.SMART_EXPERIMENTAL))
    }

    @Test
    fun `stepping down walks the ladder and stops at basic`() {
        assertEquals(AccuracyProfileToken.ADVANCED, ModelRuntimeGuard.stepDown(AccuracyProfileToken.SMART_EXPERIMENTAL))
        assertEquals(AccuracyProfileToken.PRECISE, ModelRuntimeGuard.stepDown(AccuracyProfileToken.ADVANCED))
        assertEquals(AccuracyProfileToken.BALANCED, ModelRuntimeGuard.stepDown(AccuracyProfileToken.PRECISE))
        assertEquals(AccuracyProfileToken.LIGHT, ModelRuntimeGuard.stepDown(AccuracyProfileToken.BALANCED))
        assertEquals(AccuracyProfileToken.BASIC, ModelRuntimeGuard.stepDown(AccuracyProfileToken.LIGHT))
        assertEquals(AccuracyProfileToken.BASIC, ModelRuntimeGuard.stepDown(AccuracyProfileToken.BASIC))
    }

    @Test
    fun `downgrade keeps the requested profile when the heap fits`() {
        assertEquals(
            AccuracyProfileToken.ADVANCED,
            ModelRuntimeGuard.downgradeUntilFits(AccuracyProfileToken.ADVANCED, 2_048),
        )
    }

    @Test
    fun `downgrade stops at the first profile the heap allows`() {
        assertEquals(
            AccuracyProfileToken.PRECISE,
            ModelRuntimeGuard.downgradeUntilFits(AccuracyProfileToken.ADVANCED, 300),
        )
        assertEquals(
            AccuracyProfileToken.BASIC,
            ModelRuntimeGuard.downgradeUntilFits(AccuracyProfileToken.SMART_EXPERIMENTAL, 10),
        )
    }

    @Test
    fun `benchmark keeps the requested profile on a roomy heap`() {
        val guard = ModelRuntimeGuard(availableHeapMbProvider = { 2_048 })
        val report = guard.benchmark(AccuracyProfileToken.PRECISE)
        assertEquals(AccuracyProfileToken.PRECISE, report.requestedProfile)
        assertEquals(AccuracyProfileToken.PRECISE, report.recommendedProfile)
        assertEquals(2_048, report.availableHeapMb)
        assertTrue("latency is always at least 1ms so it can be shown", report.latencyMs >= 1L)
        assertNull(report.downgradedReason)
    }

    @Test
    fun `benchmark downgrades and explains why when the heap is small`() {
        val guard = ModelRuntimeGuard(availableHeapMbProvider = { 100 })
        val report = guard.benchmark(AccuracyProfileToken.ADVANCED)
        assertEquals(AccuracyProfileToken.ADVANCED, report.requestedProfile)
        assertEquals(AccuracyProfileToken.LIGHT, report.recommendedProfile)
        assertNotNull(report.downgradedReason)
        assertTrue(report.downgradedReason!!.contains("100MB"))
    }

    @Test
    fun `runGuarded returns the block result when it fits the budget`() = runBlocking {
        val guard = ModelRuntimeGuard()
        val value = guard.runGuarded(
            profile = AccuracyProfileToken.LIGHT,
            fallbackValue = { "fallback" },
        ) { "fresh" }
        assertEquals("fresh", value)
    }

    @Test
    fun `runGuarded falls back and reports a downgrade when the block throws`() = runBlocking {
        val guard = ModelRuntimeGuard()
        var reported: Pair<AccuracyProfileToken, String>? = null
        val value = guard.runGuarded(
            profile = AccuracyProfileToken.ADVANCED,
            onDowngrade = { profile, reason -> reported = profile to reason },
            fallbackValue = { "fallback" },
        ) {
            error("the model exploded")
        }
        assertEquals("fallback", value)
        assertEquals(AccuracyProfileToken.PRECISE, reported?.first)
        assertTrue(reported?.second?.contains("the model exploded") == true)
    }

    @Test
    fun `runGuarded falls back when the block needs a profile that is not there`() = runBlocking {
        // CALCULATED: a LiteRT profile would exceed a 50ms budget, so a slow block must not be
        // allowed to stutter playback; the fallback value wins.
        val guard = ModelRuntimeGuard()
        val value = guard.runGuarded(
            profile = AccuracyProfileToken.BASIC,
            fallbackValue = { 0 },
        ) {
            Thread.sleep(200)
            1
        }
        assertEquals(0, value)
    }
}

class StaticEmbedderScorerTest {

    @Test
    fun `cosine similarity is one for identical vectors`() {
        assertEquals(1f, StaticEmbedderScorer.cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), 1e-6f)
    }

    @Test
    fun `cosine similarity is zero for a zero vector`() {
        assertEquals(0f, StaticEmbedderScorer.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 1e-6f)
    }

    @Test
    fun `cosine similarity is negative for opposite vectors`() {
        assertEquals(-1f, StaticEmbedderScorer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun `ranking prefers the gloss that shares the most context`() {
        val ranked = StaticEmbedderScorer.rankSensesByOverlap(
            contextText = "she gave up smoking last year",
            candidates = listOf(
                1L to "to stop doing something",
                2L to "to surrender or hand over",
                3L to "to quit smoking",
            ),
        )
        assertEquals(3L, ranked.first().first)
    }

    @Test
    fun `ranking of no candidates is empty`() {
        assertTrue(StaticEmbedderScorer.rankSensesByOverlap("anything", emptyList()).isEmpty())
    }
}

class LlmSenseSelectorTest {

    @Test
    fun `prompt numbers the candidates from one`() {
        val prompt = LlmSenseSelector.buildPrompt("give up", "she gave up", listOf("quit", "surrender"))
        assertTrue(prompt.contains("1. quit"))
        assertTrue(prompt.contains("2. surrender"))
    }

    @Test
    fun `parser reads the number from a chatty answer`() {
        assertEquals(0, LlmSenseSelector.parseSelectedIndex("1", 3))
        assertEquals(2, LlmSenseSelector.parseSelectedIndex("The answer is 3.", 3))
    }

    @Test
    fun `parser clamps out of range answers`() {
        assertEquals(2, LlmSenseSelector.parseSelectedIndex("99", 3))
        assertEquals(0, LlmSenseSelector.parseSelectedIndex("0", 3))
    }

    @Test
    fun `parser falls back to the first candidate when there is no number`() {
        assertEquals(0, LlmSenseSelector.parseSelectedIndex("I am not sure", 3))
        assertEquals(0, LlmSenseSelector.parseSelectedIndex("1", 0))
    }
}