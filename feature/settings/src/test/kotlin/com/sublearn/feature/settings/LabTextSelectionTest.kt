package com.sublearn.feature.settings

import com.sublearn.core.nlp.PhraseKind
import com.sublearn.core.nlp.PhraseSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lab's free-text selection rules (D-93): what a selection of words means. */
class LabTextSelectionTest {

    private val text = "Turn the offer down, please."
    private val words = LabTextSelection.words(text)

    /** "turn ... down" covers the split phrase: the words inside its range are 0..3. */
    private fun phrase(confidence: Float = 0.9f) = PhraseSpan(
        startChar = 0,
        endChar = text.indexOf("down") + "down".length,
        tokens = listOf(0, 1, 2, 3),
        kind = PhraseKind.PHRASAL,
        headword = "turn down",
        lemmaKey = "turn down",
        confidence = confidence,
        separable = true,
    )

    private fun analysis(spans: List<PhraseSpan>) = LabTextAnalysis(
        text = text,
        words = words,
        spans = spans,
        wordInfo = words.map { null },
        spanInfo = spans.map { null },
    )

    @Test fun wordsSkipPunctuationAndNumbersAndAreNumberedInOrder() {
        val numbered = LabTextSelection.words("Hi, 42 cats!")
        assertEquals(listOf("Hi", "cats"), numbered.map { it.text })
        assertEquals(listOf(0, 1), numbered.map { it.index })
        assertEquals("cats", "Hi, 42 cats!".substring(numbered[1].start, numbered[1].end))
    }

    @Test fun phraseMatchesOnlyWhenEveryWordInsideItIsSelected() {
        val span = phrase()
        assertEquals(setOf(0, 1, 2, 3), LabTextSelection.wordsIn(span, words))
        assertEquals(span, LabTextSelection.exactSpan(setOf(0, 1, 2, 3), listOf(span), words))
        // "turn down" without the object is not the phrase the analyser found.
        assertNull(LabTextSelection.exactSpan(setOf(0, 3), listOf(span), words))
        assertNull(LabTextSelection.exactSpan(emptySet(), listOf(span), words))
    }

    @Test fun exactMatchPrefersTheMostConfidentPhrase() {
        val weak = phrase(confidence = 0.4f)
        val strong = phrase(confidence = 0.8f)
        assertEquals(strong, LabTextSelection.exactSpan(setOf(0, 1, 2, 3), listOf(weak, strong), words))
    }

    @Test fun toggleAddsAndRemoves() {
        assertEquals(setOf(2), LabTextSelection.toggle(emptySet(), 2))
        assertEquals(emptySet<Int>(), LabTextSelection.toggle(setOf(2), 2))
    }

    @Test fun resolveSaysEmptyPhraseOrWords() {
        val a = analysis(listOf(phrase()))
        assertEquals(LabSelectionResult.Empty, LabTextSelection.resolve(a, emptySet()))
        val exact = LabTextSelection.resolve(a, setOf(0, 1, 2, 3))
        assertTrue(exact is LabSelectionResult.Phrase)
        assertEquals("turn down", (exact as LabSelectionResult.Phrase).span.headword)
    }

    @Test fun resolveShowsWordsAndTheOverlappingPhraseWhenNotExact() {
        val a = analysis(listOf(phrase()))
        val partial = LabTextSelection.resolve(a, setOf(3))
        assertTrue(partial is LabSelectionResult.Words)
        partial as LabSelectionResult.Words
        assertEquals(listOf("down"), partial.words.map { it.text })
        assertEquals(1, partial.partial.size)
    }

    @Test fun coveredWordsAreTheWordsOfEveryFoundPhrase() {
        assertEquals(setOf(0, 1, 2, 3), LabTextSelection.coveredWords(listOf(phrase()), words))
        assertTrue(LabTextSelection.coveredWords(emptyList(), words).isEmpty())
    }
}
