package com.sublearn.feature.player

import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.LearningSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningCardSelectionTest {
    private val learning = LearningSettings(mode = LearningMode.LEARNING, manualLevel = CefrLevel.B1)
    private val hardWord = LearningCandidate("reluctant", level = CefrLevel.B2)

    @Test
    fun `entertainment and off never select automatic cards`() {
        for (mode in listOf(LearningMode.ENTERTAINMENT, LearningMode.OFF)) {
            assertTrue(selectLearningCandidates(listOf(hardWord), learning.copy(mode = mode)).isEmpty())
        }
    }

    @Test
    fun `above means strictly above not equal or unknown`() {
        val candidates = listOf(
            LearningCandidate("easy", level = CefrLevel.A1),
            LearningCandidate("equal", level = CefrLevel.B1),
            LearningCandidate("unknown", level = CefrLevel.UNKNOWN),
            LearningCandidate("missing"),
            hardWord,
        )
        assertEquals(listOf(hardWord), selectLearningCandidates(candidates, learning))
    }

    @Test
    fun `known words and phrases stay out of automatic hints`() {
        val candidates = listOf(hardWord.copy(known = true), LearningCandidate("give up", level = CefrLevel.B2, known = true))
        assertTrue(selectLearningCandidates(candidates, learning).isEmpty())
    }

    @Test
    fun `phrases are prioritized and inflections deduplicated`() {
        val phrase = LearningCandidate("give up", level = CefrLevel.B2, phrase = true)
        val candidates = listOf(hardWord, hardWord.copy(query = "reluctantly", lemma = "reluctant"), phrase)
        assertEquals(listOf(phrase, hardWord), selectLearningCandidates(candidates, learning))
    }

    @Test
    fun `count zero disables hints and count limit applies`() {
        val candidates = (1..20).map { hardWord.copy(query = "word $it", lemma = "word $it") }
        assertTrue(selectLearningCandidates(candidates, learning.copy(maxPopupCards = 0)).isEmpty())
        assertEquals(2, selectLearningCandidates(candidates, learning.copy(maxPopupCards = 2)).size)
        assertEquals(10, selectLearningCandidates(candidates, learning.copy(maxPopupCards = 100)).size)
    }

    @Test
    fun `turning off above-only includes unknown words but never known words`() {
        val candidate = LearningCandidate("unlisted")
        val settings = learning.copy(showAboveLevelOnly = false)
        assertEquals(listOf(candidate), selectLearningCandidates(listOf(candidate, hardWord.copy(known = true)), settings))
    }

    @Test
    fun `raising user level immediately removes equal-level hints`() {
        assertTrue(selectLearningCandidates(listOf(hardWord), learning.copy(manualLevel = CefrLevel.B2)).isEmpty())
    }
}
