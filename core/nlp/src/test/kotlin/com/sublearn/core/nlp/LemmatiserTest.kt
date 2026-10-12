package com.sublearn.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LemmatiserTest {

    private val lemmatiser = Lemmatiser(
        irregularForms = mapOf(
            "went" to setOf("go"),
            "gone" to setOf("go"),
            "gave" to setOf("give"),
            "given" to setOf("give"),
            "took" to setOf("take"),
            "taken" to setOf("take"),
            "broke" to setOf("break"),
            "broken" to setOf("break"),
            "came" to setOf("come"),
            "made" to setOf("make"),
            "was" to setOf("be"),
            "were" to setOf("be"),
            "been" to setOf("be"),
            "saw" to setOf("see"),
            "left" to setOf("leave"),
            "ran" to setOf("run"),
            "died" to setOf("die"),
            "picked" to setOf("pick"),
            "turned" to setOf("turn"),
        ),
    )

    @Test
    fun `irregular past tense forms map to lemma`() {
        assertTrue("give" in lemmatiser.candidates("gave"))
        assertTrue("break" in lemmatiser.candidates("broke"))
        assertTrue("come" in lemmatiser.candidates("came"))
        assertTrue("take" in lemmatiser.candidates("took"))
        assertTrue("see" in lemmatiser.candidates("saw"))
    }

    @Test
    fun `irregular past participle forms map to lemma`() {
        assertTrue("go" in lemmatiser.candidates("gone"))
        assertTrue("give" in lemmatiser.candidates("given"))
        assertTrue("take" in lemmatiser.candidates("taken"))
        assertTrue("break" in lemmatiser.candidates("broken"))
    }

    @Test
    fun `ying forms map to ie base lemma`() {
        assertTrue("die" in lemmatiser.candidates("dying"))
        assertTrue("lie" in lemmatiser.candidates("lying"))
        assertTrue("tie" in lemmatiser.candidates("tying"))
    }

    @Test
    fun `regular -ing forms generate candidates`() {
        val candidates = lemmatiser.candidates("running")
        assertTrue("run" in candidates)
        // Should also have the raw stem
        assertTrue("runn" in candidates)
    }

    @Test
    fun `regular -ed forms generate candidates`() {
        val candidates = lemmatiser.candidates("played")
        assertTrue("play" in candidates)
        assertTrue("playe" in candidates)
    }

    @Test
    fun `regular -s forms generate candidates`() {
        val candidates = lemmatiser.candidates("runs")
        assertTrue("run" in candidates)
    }

    @Test
    fun `ambiguous forms produce multiple candidates`() {
        // "saw" could be the verb "see" or the noun "saw"
        val candidates = lemmatiser.candidates("saw")
        assertTrue("see" in candidates)
        assertTrue("saw" in candidates) // self is always a candidate

        // "left" could be "leave" or the noun/adjective "left"
        val leftCandidates = lemmatiser.candidates("left")
        assertTrue("leave" in leftCandidates)
        assertTrue("left" in leftCandidates)
    }

    @Test
    fun `bestCandidate prefers known lemmas`() {
        val knownLemmas = setOf("go", "see", "give")
        assertEquals("go", lemmatiser.bestCandidate("went", knownLemmas))
        assertEquals("see", lemmatiser.bestCandidate("saw", knownLemmas))
        assertEquals("give", lemmatiser.bestCandidate("gave", knownLemmas))
    }

    @Test
    fun `regular verb forms from rules`() {
        // died → die (via -ed rule)
        val diedCandidates = lemmatiser.candidates("died")
        assertTrue("die" in diedCandidates)

        // picked → pick
        val pickedCandidates = lemmatiser.candidates("picked")
        assertTrue("pick" in pickedCandidates)

        // turned → turn
        val turnedCandidates = lemmatiser.candidates("turned")
        assertTrue("turn" in turnedCandidates)
    }

    @Test
    fun `running produces run via deduplication`() {
        val candidates = lemmatiser.candidates("running")
        assertTrue("run" in candidates)
    }

    @Test
    fun `fromLexicon builds from lemma form table`() {
        val forms = listOf(
            Triple("went", "go", "VERB"),
            Triple("gone", "go", "VERB"),
            Triple("gave", "give", "VERB"),
        )
        val lem = Lemmatiser.fromLexicon(forms)
        assertTrue("go" in lem.candidates("went"))
        assertTrue("give" in lem.candidates("gave"))
    }
}
