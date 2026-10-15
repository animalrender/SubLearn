package com.sublearn.core.nlp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueStreamPhraseAnalyzerTest {

    private fun buildLexicon(): LexiconAccess {
        val lemmatiser = Lemmatiser(
            irregularForms = mapOf(
                "gave" to setOf("give"),
                "came" to setOf("come"),
                "made" to setOf("make"),
                "picked" to setOf("pick"),
                "turned" to setOf("turn"),
            ),
        )
        val entries = listOf(
            LexiconEntry(
                id = 1, key = "come up with", headword = "come up with",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B2",
                patterns = listOf(LexiconPattern(1, "come up with", "come", separable = false, minTokens = 3, maxTokens = 5)),
            ),
            LexiconEntry(
                id = 2, key = "make up one's mind", headword = "make up one's mind",
                kind = PhraseKind.IDIOM, pos = null, cefr = "B2",
                patterns = listOf(LexiconPattern(2, "make up POSS mind", "mind", separable = false, minTokens = 4, maxTokens = 4)),
            ),
            LexiconEntry(
                id = 3, key = "give up", headword = "give up",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(LexiconPattern(3, "give up", "give", separable = true, minTokens = 2, maxTokens = 5)),
            ),
        )
        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        return object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lemmatiser.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }
    }

    @Test
    fun `detects phrase split across two consecutive cues within gap`() = runBlocking {
        val analyzer = CueStreamPhraseAnalyzer()
        val cues = listOf(
            CueSlice(cueId = 1L, blockId = 10L, startMs = 1000L, endMs = 2500L, text = "She finally came"),
            CueSlice(cueId = 2L, blockId = 11L, startMs = 2700L, endMs = 4200L, text = "up with a brilliant plan."),
        )
        val result = analyzer.analyze(cues, buildLexicon(), NlpConfig.DEFAULT, LexiconPosTagger())
        val block10Spans = result[10L].orEmpty()
        val block11Spans = result[11L].orEmpty()

        assertTrue("Block 10 should contain part of 'come up with'", block10Spans.any { it.headword == "come up with" && it.isCrossBlock })
        assertTrue("Block 11 should contain part of 'come up with'", block11Spans.any { it.headword == "come up with" && it.isCrossBlock })
        assertEquals(2, block10Spans.first().parts.size)
    }

    @Test
    fun `does not bridge across terminal sentence punctuation`() = runBlocking {
        val analyzer = CueStreamPhraseAnalyzer()
        val cues = listOf(
            CueSlice(cueId = 1L, blockId = 1L, startMs = 1000L, endMs = 2000L, text = "He never gave."),
            CueSlice(cueId = 2L, blockId = 2L, startMs = 2100L, endMs = 3000L, text = "Up on the hill."),
        )
        val result = analyzer.analyze(cues, buildLexicon(), NlpConfig.DEFAULT)
        assertTrue(result[1L].orEmpty().isEmpty())
        assertTrue(result[2L].orEmpty().isEmpty())
    }

    @Test
    fun `does not bridge when gap exceeds hardGapMs`() = runBlocking {
        val analyzer = CueStreamPhraseAnalyzer()
        val cues = listOf(
            CueSlice(cueId = 1L, blockId = 1L, startMs = 1000L, endMs = 2000L, text = "She finally came"),
            CueSlice(cueId = 2L, blockId = 2L, startMs = 5000L, endMs = 6500L, text = "up with a plan"),
        )
        val segConfig = SegmentConfig(hardGapMs = 1200L)
        val result = analyzer.analyze(cues, buildLexicon(), NlpConfig.DEFAULT, segmentConfig = segConfig)
        assertTrue(result[1L].orEmpty().isEmpty())
        assertTrue(result[2L].orEmpty().isEmpty())
    }

    @Test
    fun `text cleanup strips speaker labels and HTML tags`() {
        val cleaned = AnalysisTextCleanup.clean("<i>JOHN: I gave up [MUSIC] smoking.</i>")
        assertFalse("Should strip HTML tags", cleaned.contains("<i>"))
        assertFalse("Should strip speaker label", cleaned.contains("JOHN:"))
        assertFalse("Should strip stage direction", cleaned.contains("[MUSIC]"))
        assertTrue("Should contain core text", cleaned.contains("gave up"))
    }

    @Test
    fun `text cleanup normalizes curly quotes`() {
        val cleaned = AnalysisTextCleanup.clean("She\u2019s going to \u201Cgive up\u201D")
        assertTrue("Should normalize curly single quote", cleaned.contains("'s"))
        assertFalse("Should not contain curly double quotes", cleaned.contains("\u201C"))
    }

    @Test
    fun `computeContentHash produces stable hash`() {
        val cues = listOf(
            CueSlice(1L, 10L, 0L, 1000L, "Hello world"),
            CueSlice(2L, 11L, 1100L, 2000L, "Goodbye world"),
        )
        val hash1 = CueStreamPhraseAnalyzer.computeContentHash(cues)
        val hash2 = CueStreamPhraseAnalyzer.computeContentHash(cues)
        assertEquals("Same input should produce same hash", hash1, hash2)
        assertTrue("Hash should be 64 hex chars", hash1.length == 64)
    }

    @Test
    fun `lexicon pos tagger disambiguates particle vs preposition and noun vs verb`() = runBlocking {
        val priors = mapOf(
            "break" to mapOf("VERB" to 55, "NOUN" to 45),
            "walked" to mapOf("VERB" to 100),
            "picked" to mapOf("VERB" to 100),
            "book" to mapOf("NOUN" to 90, "VERB" to 10),
            "hill" to mapOf("NOUN" to 100),
        )
        val tagger = LexiconPosTagger(
            wordPosProvider = { words -> words.mapNotNull { w -> priors[w]?.let { w to it } }.toMap() },
        )

        val tags1 = tagger.tag(listOf("She", "picked", "the", "book", "up"))
        assertEquals("PART", tags1[4])
        assertEquals("NOUN", tags1[3])

        val tags2 = tagger.tag(listOf("She", "walked", "up", "the", "hill"))
        assertEquals("PREP", tags2[2])

        val tags3 = tagger.tag(listOf("Let", "us", "take", "a", "break"))
        assertEquals("NOUN", tags3[4])

        val tags4 = tagger.tag(listOf("It", "can", "break"))
        assertEquals("VERB", tags4[2])
        assertFalse(tags3[4] == tags4[2])
    }

    @Test
    fun `hard boundary start detection`() {
        assertTrue(CueStreamPhraseAnalyzer.endsWithSentenceBoundary("He said."))
        assertTrue(CueStreamPhraseAnalyzer.endsWithSentenceBoundary("Really?"))
        assertFalse(CueStreamPhraseAnalyzer.endsWithSentenceBoundary("Wait..."))
        assertFalse(CueStreamPhraseAnalyzer.endsWithSentenceBoundary("..."))
    }
}