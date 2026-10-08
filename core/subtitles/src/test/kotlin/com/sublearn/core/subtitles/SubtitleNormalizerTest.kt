package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleNormalizerTest {
    @Test
    fun `removes line breaks inside a block`() {
        assertEquals("a b c", SubtitleNormalizer.removeLineBreaks("a\nb\nc"))
        assertEquals("one two", SubtitleNormalizer.removeLineBreaks("one\n   two"))
    }

    @Test
    fun `fixes spacing around punctuation`() {
        assertEquals("Hi, world.", SubtitleNormalizer.fixPunctuation("Hi ,world."))
        assertEquals("Wait…", SubtitleNormalizer.fixPunctuation("  Wait…  "))
    }

    @Test
    fun `strips ass and html markup`() {
        assertEquals("clean", SubtitleNormalizer.stripMarkup("{\\an8}<i>clean</i>"))
    }

    @Test
    fun `collapses runs of spaces and blank lines`() {
        assertEquals("a b\nc", SubtitleNormalizer.collapseSpaces("a    b\n\n\n\nc"))
    }

    @Test
    fun `splits to max characters at word boundaries only`() {
        val text = "This is a fairly long sentence that clearly needs to be split into two parts"
        val pieces = SubtitleNormalizer.splitToMaxChars(text, 40)
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.all { it.length <= 40 })
        assertEquals(text, SubtitleNormalizer.removeLineBreaks(pieces.joinToString(" ")))
    }

    @Test
    fun `a single huge word is never cut in half`() {
        val text = "Supercalifragilisticexpialidocious"
        assertEquals(listOf(text), SubtitleNormalizer.splitToMaxChars(text, 8))
    }

    @Test
    fun `regroup drops duplicates clamps overlaps and sorts by time`() {
        val cues = listOf(
            Cue(1, 1_000, 3_000, "Hello", "t"),
            Cue(2, 2_000, 5_000, "Hello", "t"),
            Cue(3, 6_000, 6_020, "", "t"),
            Cue(4, 4_500, 7_000, "World", "t"),
        )
        val result = SubtitleNormalizer.regroup(cues, NormalizerConfig())
        assertEquals(listOf("Hello", "World"), result.map { it.text })
        assertTrue(result[0].endMs <= result[1].startMs)
    }

    @Test
    fun `sentence ends are recognised through closing quotes`() {
        assertTrue(SubtitleNormalizer.endsSentence("He said \"stop!\""))
        assertTrue(SubtitleNormalizer.endsSentence("Okay."))
        assertTrue(!SubtitleNormalizer.endsSentence("not finished"))
    }

    @Test
    fun `clause starts are recognised including persian va`() {
        assertTrue(SubtitleNormalizer.startsClause("and then we left"))
        assertTrue(SubtitleNormalizer.startsClause("و رفتیم"))
        assertTrue(!SubtitleNormalizer.startsClause("android is fine"))
    }
}
