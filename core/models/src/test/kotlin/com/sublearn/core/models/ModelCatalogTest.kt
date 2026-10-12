package com.sublearn.core.models

import com.sublearn.core.settings.AccuracyProfileToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelCatalogTest {

    @Test
    fun `catalog v2 parses cleanly and drops unverified pos opennlp ud`() {
        val file = File("docs/catalog/catalog-v2.json").let {
            if (it.exists()) it else File("../../docs/catalog/catalog-v2.json")
        }
        val catalog = if (file.exists()) {
            ModelCatalogParser.parse(file.readText()).getOrThrow()
        } else {
            ModelCatalogParser.bundledDefault()
        }
        assertEquals(2, catalog.version)
        assertFalse("pos-opennlp-ud must not be in catalog-v2", catalog.entries.any { it.id == "pos-opennlp-ud" })
        assertTrue(catalog.findById("sublearn-lexicon-en-fa-v2") != null)
        assertTrue(catalog.findById("sublearn-dict-fa-en-v2") != null)
        assertTrue(catalog.visibleEntries().all { it.verified && it.source.url.isNotBlank() })
    }

    @Test
    fun `runtime guard downgrades profile when heap is below threshold or block throws`() = runBlocking {
        val guardLowRam = ModelRuntimeGuard(availableHeapMbProvider = { 100 })
        val report = guardLowRam.benchmark(AccuracyProfileToken.SMART_EXPERIMENTAL)
        assertEquals(AccuracyProfileToken.LIGHT, report.recommendedProfile)

        var downgradedTo: AccuracyProfileToken? = null
        val res = guardLowRam.runGuarded(
            profile = AccuracyProfileToken.PRECISE,
            onDowngrade = { next, _ -> downgradedTo = next },
            fallbackValue = { "fallback" },
        ) {
            throw OutOfMemoryError("simulated OOM")
        }
        assertEquals("fallback", res)
        assertEquals(AccuracyProfileToken.BALANCED, downgradedTo)
    }

    @Test
    fun `static embedder and llm sense selector score and parse deterministically`() {
        val sim = StaticEmbedderScorer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))
        assertEquals(1f, sim, 1e-4f)

        val prompt = LlmSenseSelector.buildPrompt(
            targetWordOrPhrase = "bank",
            subtitleLine = "We sat by the river bank.",
            candidateGlosses = listOf("بانک مالی", "ساحل رودخانه"),
        )
        assertTrue(prompt.contains("river bank"))
        assertEquals(1, LlmSenseSelector.parseSelectedIndex("The best answer is 2.", 2))
        assertEquals(0, LlmSenseSelector.parseSelectedIndex("invalid", 2))
    }
}
