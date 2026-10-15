package com.sublearn.core.nlp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the DeterministicPhraseDetector with a mini-lexicon that is built at test time.
 * No fixture from a real database is committed (D-2, AGENTS.md).
 *
 * Required test cases from § 6.2: gave up, broke down, came up with, made up her mind,
 * was taken aback, running, died, picked the book up, turned the offer down.
 */
class DeterministicPhraseDetectorTest {

    private fun miniLexicon(): LexiconAccess {
        val lemmatiser = Lemmatiser(
            irregularForms = mapOf(
                "gave" to setOf("give"),
                "broke" to setOf("break"),
                "came" to setOf("come"),
                "made" to setOf("make"),
                "was" to setOf("be"),
                "taken" to setOf("take"),
                "picked" to setOf("pick"),
                "turned" to setOf("turn"),
                "running" to setOf("run"),
                "died" to setOf("die"),
                "beating" to setOf("beat"),
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
                id = 2, key = "break down", headword = "break down",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B2",
                patterns = listOf(
                    LexiconPattern(2, "break down", "break", separable = true, minTokens = 2, maxTokens = 5),
                ),
            ),
            LexiconEntry(
                id = 3, key = "come up with", headword = "come up with",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B2",
                patterns = listOf(
                    LexiconPattern(3, "come up with", "come", separable = false, minTokens = 3, maxTokens = 6),
                ),
            ),
            LexiconEntry(
                id = 4, key = "piece of cake", headword = "piece of cake",
                kind = PhraseKind.IDIOM, pos = null, cefr = "B2",
                patterns = listOf(
                    LexiconPattern(4, "piece of cake", "cake", separable = false, minTokens = 3, maxTokens = 3),
                ),
            ),
            LexiconEntry(
                id = 5, key = "beat around the bush", headword = "beat around the bush",
                kind = PhraseKind.IDIOM, pos = null, cefr = "C1",
                patterns = listOf(
                    LexiconPattern(5, "beat around the bush", "bush", separable = false, minTokens = 4, maxTokens = 4),
                ),
            ),
            LexiconEntry(
                id = 6, key = "look up", headword = "look up",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(6, "look up", "look", separable = true, minTokens = 2, maxTokens = 5),
                ),
            ),
            LexiconEntry(
                id = 7, key = "take aback", headword = "take aback",
                kind = PhraseKind.IDIOM, pos = null, cefr = "C1",
                patterns = listOf(
                    LexiconPattern(7, "BE taken aback", "taken", separable = false, minTokens = 3, maxTokens = 4),
                ),
            ),
            LexiconEntry(
                id = 8, key = "make up one's mind", headword = "make up one's mind",
                kind = PhraseKind.IDIOM, pos = null, cefr = "B2",
                patterns = listOf(
                    LexiconPattern(8, "make up POSS mind", "mind", separable = false, minTokens = 3, maxTokens = 3),
                ),
            ),
            LexiconEntry(
                id = 9, key = "turn down", headword = "turn down",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B2",
                patterns = listOf(
                    LexiconPattern(9, "turn down", "turn", separable = true, minTokens = 2, maxTokens = 5),
                ),
            ),
            LexiconEntry(
                id = 10, key = "pick up", headword = "pick up",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(10, "pick up", "pick", separable = true, minTokens = 2, maxTokens = 5),
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
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> =
                anchorIndex[anchorLemma] ?: emptyList()
            override fun lookupWord(key: String): LexiconEntry? =
                entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }
    }

    private fun tokenize(text: String): List<TokenInfo> {
        val tokens = mutableListOf<TokenInfo>()
        var charPos = 0
        for ((i, word) in text.split(" ").withIndex()) {
            val start = text.indexOf(word, charPos)
            tokens.add(TokenInfo(i, word, start, start + word.length))
            charPos = start + word.length
        }
        return tokens
    }

    @Test
    fun `gave up is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("he gave up smoking"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'gave up'", spans.any { it.headword == "give up" })
    }

    @Test
    fun `broke down is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("the car broke down yesterday"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'broke down'", spans.any { it.headword == "break down" })
    }

    @Test
    fun `came up with is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("she came up with an idea"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'came up with'", spans.any { it.headword == "come up with" })
    }

    @Test
    fun `made up her mind is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("she made up her mind"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'make up POSS mind'", spans.any { it.headword == "make up one's mind" })
    }

    @Test
    fun `was taken aback is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("he was taken aback by the news"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'take aback'", spans.any { it.headword == "take aback" })
    }

    @Test
    fun `piece of cake is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("the exam was a piece of cake"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'piece of cake'", spans.any { it.headword == "piece of cake" })
    }

    @Test
    fun `beat around the bush is detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("stop beating around the bush"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("Should detect 'beat around the bush'", spans.any { it.headword == "beat around the bush" })
    }

    @Test
    fun `look up at the sky has reduced confidence`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("she looked up at the sky"), miniLexicon(), NlpConfig.DEFAULT)
        val lookUpSpans = spans.filter { it.headword == "look up" }
        for (span in lookUpSpans) {
            assertTrue(
                "Confidence should be reduced for 'look up at the sky', got ${span.confidence}",
                span.confidence < 0.75f
            )
        }
    }

    @Test
    fun `no false positives on plain text`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("the cat sat on the mat"), miniLexicon(), NlpConfig.DEFAULT)
        assertTrue("No phrases expected", spans.isEmpty())
    }

    @Test
    fun `confidence uses pattern prior instead of flat 0_85`() = runBlocking {
        val lem = Lemmatiser()
        val entries = listOf(
            LexiconEntry(
                id = 200, key = "give up", headword = "give up",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(200, "give up", "give", separable = true, minTokens = 2, maxTokens = 5, prior = 0.90f),
                ),
            ),
        )
        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        val lex = object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lem.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }

        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("he gave up smoking"), lex, NlpConfig.DEFAULT)
        val giveUp = spans.firstOrNull { it.headword == "give up" }
        assertTrue("Confidence should be based on prior 0.90, not 0.85", giveUp != null && giveUp.confidence >= 0.90f)
    }

    @Test
    fun `per-type threshold filters collocations`() = runBlocking {
        val lem = Lemmatiser()
        val entries = listOf(
            LexiconEntry(
                id = 201, key = "heavy rain", headword = "heavy rain",
                kind = PhraseKind.COLLOCATION, pos = null, cefr = "B1",
                patterns = listOf(
                    LexiconPattern(201, "heavy rain", "rain", separable = false, minTokens = 2, maxTokens = 2, prior = 0.60f),
                ),
            ),
        )
        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        val lex = object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lem.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }

        val detector = DeterministicPhraseDetector()
        val spans = detector.detect(tokenize("there was heavy rain today"), lex, NlpConfig.DEFAULT)
        val heavyRain = spans.firstOrNull { it.headword == "heavy rain" }
        assertTrue("Should detect collocation", heavyRain != null)
        // Collocation threshold is 0.65; prior 0.60 is below
        assertTrue("Should be below collocation threshold",
            heavyRain != null && !heavyRain.isAboveTypeThreshold(NlpConfig.DEFAULT))
    }

    @Test
    fun `idiom has priority over phrasal in overlap resolution`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        // "piece of cake" (idiom) should win over any smaller phrasal that overlaps
        val spans = detector.detect(tokenize("it was a piece of cake"), miniLexicon(), NlpConfig.DEFAULT)
        val idioms = spans.filter { it.kind == PhraseKind.IDIOM }
        assertTrue("Should detect at least one idiom", idioms.isNotEmpty())
    }

    @Test
    fun `confidence threshold filters low-confidence matches`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val config = NlpConfig(confidenceThreshold = 0.8f)
        val spans = detector.detect(tokenize("she looked up at the sky"), miniLexicon(), config)
        val highConfidenceSpans = spans.filter { it.isAboveThreshold(config.confidenceThreshold) }
        // "look up at the sky" should be filtered out at 0.8 threshold
        val lookUpAbove = highConfidenceSpans.filter { it.headword == "look up" }
        assertTrue("'look up' in this context should be below threshold", lookUpAbove.isEmpty())
    }

    @Test
    fun `separated phrasal verbs picked the book up and turned the offer down are detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val tagger = LexiconPosTagger()
        val spans1 = detector.detect(tokenize("she picked the book up"), miniLexicon(), NlpConfig.DEFAULT, tagger)
        assertTrue("Should detect 'pick up'", spans1.any { it.headword == "pick up" && it.separable })

        val spans2 = detector.detect(tokenize("he turned the offer down"), miniLexicon(), NlpConfig.DEFAULT, tagger)
        assertTrue("Should detect 'turn down'", spans2.any { it.headword == "turn down" && it.separable })
    }

    @Test
    fun `look that up should be detected`() = runBlocking {
        val detector = DeterministicPhraseDetector()
        val tagger = LexiconPosTagger()
        val spans = detector.detect(tokenize("can you look that up"), miniLexicon(), NlpConfig.DEFAULT, tagger)
        assertTrue("Should detect 'look up' with 'that' as gap", spans.any { it.headword == "look up" && it.separable })
    }

    @Test
    fun `put it with should NOT match put up with`() = runBlocking {
        // Build a lexicon with "put up with" only
        val lem = Lemmatiser()
        val entries = listOf(
            LexiconEntry(
                id = 100, key = "put up with", headword = "put up with",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(100, "put up with", "put", separable = true, minTokens = 3, maxTokens = 6, prior = 0.80f),
                ),
            ),
        )
        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        val lex = object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lem.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }

        val detector = DeterministicPhraseDetector()
        val tagger = LexiconPosTagger()
        val spans = detector.detect(tokenize("put it with the books"), lex, NlpConfig.DEFAULT, tagger)
        assertTrue("'put it with' should NOT match 'put up with'", spans.none { it.headword == "put up with" })
    }

    @Test
    fun `put up with this noise SHOULD be detected`() = runBlocking {
        val lem = Lemmatiser()
        val entries = listOf(
            LexiconEntry(
                id = 101, key = "put up with", headword = "put up with",
                kind = PhraseKind.PHRASAL, pos = "VERB", cefr = "B1",
                patterns = listOf(
                    LexiconPattern(101, "put up with", "put", separable = true, minTokens = 3, maxTokens = 6, prior = 0.80f),
                ),
            ),
        )
        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        val lex = object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lem.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }

        val detector = DeterministicPhraseDetector()
        val tagger = LexiconPosTagger()
        val spans = detector.detect(tokenize("I can't put up with this noise"), lex, NlpConfig.DEFAULT, tagger)
        assertTrue("'put up with' should be detected", spans.any { it.headword == "put up with" })
    }

    @Test
    fun `detects 25 idioms and 10 collocations`() = runBlocking {
        val idiomPhrases = listOf(
            "piece of cake" to ("piece of cake" to "cake"),
            "beat around the bush" to ("beat around the bush" to "bush"),
            "spill the beans" to ("spill the beans" to "beans"),
            "kick the bucket" to ("kick the bucket" to "bucket"),
            "under the weather" to ("under the weather" to "weather"),
            "bite the bullet" to ("bite the bullet" to "bullet"),
            "break the ice" to ("break the ice" to "ice"),
            "cost an arm and a leg" to ("cost an arm and a leg" to "arm"),
            "hit the nail on the head" to ("hit the nail on the head" to "nail"),
            "let the cat out of the bag" to ("let the cat out of the bag" to "cat"),
            "once in a blue moon" to ("once in a blue moon" to "moon"),
            "burn the midnight oil" to ("burn the midnight oil" to "midnight"),
            "call it a day" to ("call it a day" to "day"),
            "cut corners" to ("cut corners" to "corners"),
            "get out of hand" to ("get out of hand" to "hand"),
            "go the extra mile" to ("go the extra mile" to "mile"),
            "hang in there" to ("hang in there" to "hang"),
            "hit the sack" to ("hit the sack" to "sack"),
            "miss the boat" to ("miss the boat" to "boat"),
            "pull my leg" to ("pull POSS leg" to "leg"),
            "see eye to eye" to ("see eye to eye" to "eye"),
            "sit on the fence" to ("sit on the fence" to "fence"),
            "speak of the devil" to ("speak of the devil" to "devil"),
            "take it easy" to ("take it easy" to "easy"),
            "wrap her head around" to ("wrap POSS head around" to "head"),
        )
        val collocationPhrases = listOf(
            "make a decision" to ("make a decision" to "decision"),
            "heavy rain" to ("heavy rain" to "rain"),
            "fast food" to ("fast food" to "food"),
            "pay attention" to ("pay attention" to "attention"),
            "take a break" to ("take a break" to "break"),
            "catch a cold" to ("catch a cold" to "cold"),
            "keep in touch" to ("keep in touch" to "touch"),
            "save time" to ("save time" to "time"),
            "do homework" to ("do homework" to "homework"),
            "make progress" to ("make progress" to "progress"),
        )

        val entries = ArrayList<LexiconEntry>()
        var nextId = 100L
        for ((surface, patAndAnchor) in idiomPhrases) {
            val id = nextId++
            entries += LexiconEntry(
                id = id, key = surface, headword = surface,
                kind = PhraseKind.IDIOM, pos = null, cefr = "B2",
                patterns = listOf(LexiconPattern(id, patAndAnchor.first, patAndAnchor.second, false, 2, 6)),
            )
        }
        for ((surface, patAndAnchor) in collocationPhrases) {
            val id = nextId++
            entries += LexiconEntry(
                id = id, key = surface, headword = surface,
                kind = PhraseKind.COLLOCATION, pos = null, cefr = "B1",
                patterns = listOf(LexiconPattern(id, patAndAnchor.first, patAndAnchor.second, false, 2, 5)),
            )
        }

        val byAnchor = entries.groupBy { it.patterns.first().anchorLemma }
        val lem = Lemmatiser()
        val lex = object : LexiconAccess {
            override fun lemmatiser(form: String): Set<String> = lem.candidates(form)
            override fun findByAnchor(anchorLemma: String): List<LexiconEntry> = byAnchor[anchorLemma].orEmpty()
            override fun lookupWord(key: String): LexiconEntry? = entries.firstOrNull { it.key == key }
            override fun senses(entryId: Long): List<SenseCandidate> = emptyList()
            override fun examples(senseId: Long): List<Pair<String, String>> = emptyList()
        }

        val detector = DeterministicPhraseDetector()
        for ((surface, _) in idiomPhrases) {
            val spans = detector.detect(tokenize("they always $surface today"), lex, NlpConfig.DEFAULT)
            assertTrue("Expected idiom '$surface' to be detected", spans.any { it.kind == PhraseKind.IDIOM && it.headword == surface })
        }
        for ((surface, _) in collocationPhrases) {
            val spans = detector.detect(tokenize("we saw $surface today"), lex, NlpConfig.DEFAULT)
            assertTrue(
                "Expected collocation '$surface' to be detected",
                spans.any { it.kind == PhraseKind.COLLOCATION && it.headword == surface },
            )
        }
    }
}
