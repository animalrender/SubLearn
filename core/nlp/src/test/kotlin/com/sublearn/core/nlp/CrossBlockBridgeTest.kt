package com.sublearn.core.nlp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the cross-block bridge for phrases that span two adjacent subtitle blocks.
 * No fixture from a real database is committed (D-2, AGENTS.md).
 */
class CrossBlockBridgeTest {

    private fun miniLexicon(): LexiconAccess {
        val lemmatiser = Lemmatiser(
            irregularForms = mapOf(
                "gave" to setOf("give"),
                "beat" to setOf("beat"),
            ),
        )
        val entries = listOf(
            LexiconEntry(
                id = 1, key = "give up", headword = "give up",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(1, "give up", "give", separable = true, minTokens = 2, maxTokens = 5),
                ),
            ),
            LexiconEntry(
                id = 2, key = "piece of cake", headword = "piece of cake",
                kind = PhraseKind.IDIOM, pos = null, cefr = "B2",
                patterns = listOf(
                    LexiconPattern(2, "piece of cake", "cake", separable = false, minTokens = 3, maxTokens = 3),
                ),
            ),
            LexiconEntry(
                id = 3, key = "beat around the bush", headword = "beat around the bush",
                kind = PhraseKind.IDIOM, pos = null, cefr = "C1",
                patterns = listOf(
                    LexiconPattern(3, "beat around the bush", "bush", separable = false, minTokens = 4, maxTokens = 4),
                ),
            ),
        )
        val anchorIndex = mutableMapOf<String, MutableList<LexiconEntry>>()
        for (entry in entries) {
            for (pattern in entry.patterns) {
                anchorIndex.getOrPut(pattern.anchorLemma) { mutableListOf() }.add(entry)
            }
        }
        return object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lemmatiser.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = anchorIndex[anchorLemma] ?: emptyList()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }
    }

    private fun tokenize(text: String, startIdx: Int = 0): List<TokenInfo> {
        val tokens = mutableListOf<TokenInfo>()
        var charPos = 0
        for ((i, word) in text.split(" ").withIndex()) {
            val start = text.indexOf(word, charPos)
            tokens.add(TokenInfo(startIdx + i, word, start, start + word.length))
            charPos = start + word.length
        }
        return tokens
    }

    @Test
    fun `give up split across two blocks`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        // Block A ends with "gave", Block B starts with "up"
        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "he gave",
            tokens = tokenize("he gave", 0),
            startMs = 0, endMs = 1000,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "up smoking",
            tokens = tokenize("up smoking", 2),
            startMs = 1200, endMs = 2500,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should detect cross-block 'give up'", spans.isNotEmpty())
        val span = spans.first()
        assertEquals(PhraseKind.PHRASAL, span.kind)
        assertEquals("give up", span.headword)
        assertTrue("Span should have 2 parts", span.parts.size == 2)
        assertEquals(0, span.parts[0].blockId)
        assertEquals(1, span.parts[1].blockId)
    }

    @Test
    fun `piece of cake split across two blocks`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "that exam was a piece",
            tokens = tokenize("that exam was a piece", 0),
            startMs = 0, endMs = 2000,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "of cake to pass",
            tokens = tokenize("of cake to pass", 5),
            startMs = 2300, endMs = 4000,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should detect cross-block 'piece of cake'", spans.isNotEmpty())
        assertEquals("piece of cake", spans.first().headword)
    }

    @Test
    fun `beat around the bush split across two blocks`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "stop beating around",
            tokens = tokenize("stop beating around", 0),
            startMs = 0, endMs = 1500,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "the bush and tell me",
            tokens = tokenize("the bush and tell me", 3),
            startMs = 1800, endMs = 4000,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should detect cross-block 'beat around the bush'", spans.isNotEmpty())
        assertEquals("beat around the bush", spans.first().headword)
    }

    @Test
    fun `no bridge when gap too large`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "he gave",
            tokens = tokenize("he gave", 0),
            startMs = 0, endMs = 1000,
        )
        // Gap > 1500ms
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "up smoking",
            tokens = tokenize("up smoking", 2),
            startMs = 3000, endMs = 4500,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should not bridge when gap > 1500ms", spans.isEmpty())
    }

    @Test
    fun `no bridge when block A ends with period`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "he gave.",
            tokens = tokenize("he gave.", 0),
            startMs = 0, endMs = 1000,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "up smoking",
            tokens = tokenize("up smoking", 2),
            startMs = 1200, endMs = 2500,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should not bridge when block A ends with period", spans.isEmpty())
    }

    @Test
    fun `no bridge when block B starts with dash`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val bridge = CrossBlockBridge(detector)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "he gave",
            tokens = tokenize("he gave", 0),
            startMs = 0, endMs = 1000,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "- up smoking",
            tokens = tokenize("- up smoking", 2),
            startMs = 1200, endMs = 2500,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should not bridge when block B starts with dash", spans.isEmpty())
    }

    @Test
    fun `no bridge when disabled in config`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val config = NlpConfig(crossBlockBridgeEnabled = false)
        val bridge = CrossBlockBridge(detector, config)
        val lexicon = miniLexicon()

        val blockA = CrossBlockBridge.BridgeBlock(
            id = 0, text = "he gave",
            tokens = tokenize("he gave", 0),
            startMs = 0, endMs = 1000,
        )
        val blockB = CrossBlockBridge.BridgeBlock(
            id = 1, text = "up smoking",
            tokens = tokenize("up smoking", 2),
            startMs = 1200, endMs = 2500,
        )

        val spans = bridge.findCrossBlockPhrases(blockA, blockB, lexicon)
        assertTrue("Should not bridge when disabled", spans.isEmpty())
    }
}
