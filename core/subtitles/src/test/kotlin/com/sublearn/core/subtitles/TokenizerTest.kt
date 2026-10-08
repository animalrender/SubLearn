package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {
    @Test
    fun `splits words and punctuation with offsets`() {
        val spans = Tokenizer.spans("Hello, world!")
        assertEquals(listOf("Hello", ",", "world", "!"), spans.map { it.text })
        assertEquals(listOf(0, 5, 7, 12), spans.map { it.start })
        assertTrue(spans.filter { it.isWord }.map { it.text } == listOf("Hello", "world"))
    }

    @Test
    fun `keeps contractions together and detaches a trailing apostrophe`() {
        assertEquals(listOf("don't", "the", "girls'"), Tokenizer.words("don't say the girls'").map { it.text })
    }

    @Test
    fun `keeps the persian half space inside a word`() {
        val text = "می‌رم"
        val word = Tokenizer.words(text).single()
        assertEquals(text, word.text)
    }

    @Test
    fun `direction is decided per run`() {
        assertEquals(TextDirection.RTL, TextDirection.of("سلام"))
        assertEquals(TextDirection.LTR, TextDirection.of("hello"))
        // Numbers and punctuation do not flip the run.
        assertEquals(TextDirection.LTR, TextDirection.of("12345?!"))
        assertEquals(TextDirection.RTL, TextDirection.of("(12) رقم"))
    }

    @Test
    fun `tokens carry the estimated time of each word`() {
        val tokens = Tokenizer.toTokens("one two three", 0L, 3_000L)
        val words = tokens.filter { it.isWord }
        assertEquals(3, words.size)
        assertTrue(words[0].startMs < words[1].startMs)
        assertTrue(words[1].startMs < words[2].startMs)
        assertEquals(3_000L, words.last().endMs)
    }

    @Test
    fun `precise timings win over estimation`() {
        val timings = listOf(TokenTiming(100L, 200L), TokenTiming(900L, 1_400L))
        val tokens = Tokenizer.toTokens("one two", 0L, 3_000L, timings = timings)
        assertEquals(listOf(100L to 200L, 900L to 1_400L), tokens.filter { it.isWord }.map { it.startMs to it.endMs })
    }
}
