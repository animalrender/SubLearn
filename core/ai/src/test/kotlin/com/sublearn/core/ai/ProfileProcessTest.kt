package com.sublearn.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screen's description of a profile must agree with what the engine really runs. */
class ProfileProcessTest {

    @Test fun basicIsPackOnlyWithPriorRanking() {
        val steps = ProfileProcess.stepsFor(ProfileTier.BASIC)
        assertEquals(
            listOf(ProfileStage.PHRASES, ProfileStage.LOOKUP, ProfileStage.RANK_PRIOR),
            steps.map { it.stage },
        )
        assertTrue(steps.all { it.model == null })
    }

    @Test fun lightAddsTaggingButStillNoModel() {
        val steps = ProfileProcess.stepsFor(ProfileTier.LIGHT)
        assertTrue(steps.any { it.stage == ProfileStage.TAGGING })
        assertTrue(steps.any { it.stage == ProfileStage.RANK_PRIOR })
        assertTrue(steps.all { it.model == null })
    }

    @Test fun balancedRanksWithThePureKotlinEncoder() {
        val rank = ProfileProcess.stepsFor(ProfileTier.BALANCED).single { it.stage == ProfileStage.RANK_ENCODER }
        assertEquals(NlpModelCatalog.POTION_BASE_8M, rank.model)
    }

    @Test fun preciseRanksWithMiniLm() {
        val rank = ProfileProcess.stepsFor(ProfileTier.PRECISE).single { it.stage == ProfileStage.RANK_ENCODER }
        assertEquals(NlpModelCatalog.MINILM_L6_INT8, rank.model)
    }

    @Test fun advancedRanksWithDistilBert() {
        val rank = ProfileProcess.stepsFor(ProfileTier.ADVANCED).single { it.stage == ProfileStage.RANK_ENCODER }
        assertEquals(NlpModelCatalog.DISTILBERT_INT8, rank.model)
    }

    @Test fun smartShortlistsWithTheEncoderThenAsksTheLlm() {
        val steps = ProfileProcess.stepsFor(ProfileTier.SMART)
        assertEquals(NlpModelCatalog.DISTILBERT_INT8, steps.single { it.stage == ProfileStage.RANK_ENCODER }.model)
        assertEquals(NlpModelCatalog.QWEN25_05B, steps.single { it.stage == ProfileStage.RANK_LLM }.model)
    }

    @Test fun everyNamedModelIsOneTheProfileDownloads() {
        for (tier in ProfileTier.entries) {
            val downloads = NlpModelCatalog.modelsFor(tier).map { it.id }.toSet()
            for (step in ProfileProcess.stepsFor(tier)) {
                step.model?.let { assertTrue("$tier names ${it.id} but does not download it", it.id in downloads) }
            }
        }
    }

    @Test fun everyProfileAboveBasicTags() {
        // Every profile above Basic runs the tagger the player and the lab share.
        for (tier in listOf(ProfileTier.LIGHT, ProfileTier.BALANCED, ProfileTier.PRECISE, ProfileTier.ADVANCED, ProfileTier.SMART)) {
            assertTrue("$tier must tag", ProfileProcess.stepsFor(tier).any { it.stage == ProfileStage.TAGGING })
        }
    }
}
