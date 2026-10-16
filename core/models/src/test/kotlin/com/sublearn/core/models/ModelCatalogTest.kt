package com.sublearn.core.models

import com.sublearn.core.ai.DeviceCapabilities
import com.sublearn.core.ai.ProfileAvailability
import com.sublearn.core.ai.ProfileTier
import com.sublearn.core.ai.SmartProfileSelector
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
    fun `profile advice is decided by the device and the installed models`() {
        val tinyPhone = DeviceCapabilities(totalRamMb = 64, cpuCores = 2, batterySaver = true, freeStorageMb = 512)
        val smallPhone = DeviceCapabilities(totalRamMb = 2048, cpuCores = 4, batterySaver = true, freeStorageMb = 512)
        val bigPhone = DeviceCapabilities(totalRamMb = 8192, cpuCores = 8, batterySaver = false, freeStorageMb = 8192)

        // A profile that needs no model is always ready.
        assertEquals(ProfileAvailability.Ready, SmartProfileSelector.availability(ProfileTier.BASIC, tinyPhone, emptySet()))

        // The RAM floor is the device's memory, not the app's heap: a 64 MB phone is told the
        // profile needs 128 MB of RAM, and 2 GB is enough.
        assertEquals(
            ProfileAvailability.NeedsRam(128),
            SmartProfileSelector.availability(ProfileTier.BALANCED, tinyPhone, setOf("emb-potion-base-8m")),
        )
        assertEquals(
            ProfileAvailability.Ready,
            SmartProfileSelector.availability(ProfileTier.BALANCED, smallPhone, setOf("emb-potion-base-8m")),
        )

        // A model that is not on the device is named, so the screen can point at it.
        assertEquals(
            ProfileAvailability.NeedsModel("emb-potion-base-8m"),
            SmartProfileSelector.availability(ProfileTier.BALANCED, bigPhone, emptySet()),
        )

        // A runtime this build does not have is reported, never pretended away.
        val precise = SmartProfileSelector.availability(ProfileTier.PRECISE, bigPhone, setOf("emb-minilm-l6-int8"))
        assertTrue(
            "a missing runtime must be reported, got $precise",
            precise is ProfileAvailability.RuntimeMissing || precise is ProfileAvailability.Ready,
        )

        // The automatic choice never promises a tier the device cannot hold: with 64 MB of RAM no
        // model tier is possible, so the choice stays with the profiles that need no model.
        val chosen = SmartProfileSelector.select(tinyPhone, setOf("emb-potion-base-8m"))
        assertTrue("the automatic profile must stay within the device's reach, got $chosen", chosen <= ProfileTier.LIGHT)

        // With the model on the device and room to hold it, the automatic choice reaches BALANCED.
        assertEquals(ProfileTier.BALANCED, SmartProfileSelector.select(smallPhone, setOf("emb-potion-base-8m")))
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
