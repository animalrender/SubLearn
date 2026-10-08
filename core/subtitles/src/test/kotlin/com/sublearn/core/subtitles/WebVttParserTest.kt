package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttParserTest {
    @Test
    fun `parses header notes and cue settings`() {
        val text = """
            WEBVTT - generated

            NOTE keep this out of cues
            00:00:01.000 --> 00:00:02.500 align:start position:50%
            Hello <v Manager>world</v>

            cue-2
            00:00:03.000 --> 00:00:04.000
            Second line
        """.trimIndent()
        val cues = WebVttParser.parse(text, "t")
        assertEquals(2, cues.size)
        assertEquals("Hello world", cues[0].text)
        assertEquals("Second line", cues[1].text)
    }

    @Test
    fun `style block is skipped`() {
        val text = """
            WEBVTT

            STYLE
            ::cue { color: white }

            00:00:01.000 --> 00:00:02.000
            Real cue
        """.trimIndent()
        assertEquals("Real cue", WebVttParser.parse(text, "t").single().text)
    }

    @Test
    fun `inline timings become per-word timing`() {
        val text = """
            WEBVTT

            00:00:01.000 --> 00:00:05.000
            <00:00:01.000>One <00:00:02.000>two <00:00:04.000>three
        """.trimIndent()
        val cue = WebVttParser.parse(text, "t").single()
        assertEquals("One two three", cue.text)
        val words = cue.tokens.filter { it.isWord }
        assertEquals(listOf("One", "two", "three"), words.map { it.text })
        assertEquals(1_000L, words[0].startMs)
        assertEquals(2_000L, words[1].startMs)
        assertEquals(4_000L, words[2].startMs)
        assertTrue(words.all { it.endMs >= it.startMs })
    }
}
