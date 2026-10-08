package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {
    @Test
    fun `parses two cues`() {
        val text = """
            1
            00:00:01,000 --> 00:00:03,000
            Hello there.

            2
            00:00:03,500 --> 00:00:06,000
            Line one
            Line two
        """.trimIndent()
        val cues = SrtParser.parse(text, "t")
        assertEquals(2, cues.size)
        assertEquals("Hello there.", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("Line one\nLine two", cues[1].text)
        assertEquals(listOf(1, 2), cues.map { it.id })
    }

    @Test
    fun `tolerates crlf and missing indices`() {
        val text = "00:00:01.000 --> 00:00:02.000\r\nFirst\r\n\r\n00:00:02.500 --> 00:00:04.000\r\nSecond\r\n"
        val cues = SrtParser.parse(text, "t")
        assertEquals(listOf("First", "Second"), cues.map { it.text })
    }

    @Test
    fun `strips styling tags and entities`() {
        val text = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Hello</i> <font color="#00ff00">world</font> &amp; friends
        """.trimIndent()
        assertEquals("Hello world & friends", SrtParser.parse(text, "t").single().text)
    }

    @Test
    fun `drops cues with empty text and clamps bad ranges`() {
        val text = """
            1
            00:00:01,000 --> 00:00:02,000
            <i></i>

            2
            00:00:05,000 --> 00:00:05,000
            Late
        """.trimIndent()
        val cues = SrtParser.parse(text, "t")
        assertEquals(1, cues.size)
        assertTrue(cues[0].endMs > cues[0].startMs)
    }

    @Test
    fun `keeps raw text for lossless tools`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n<b>Bold</b>\n"
        val cue = SrtParser.parse(text, "t").single()
        assertEquals("<b>Bold</b>", cue.rawText)
        assertEquals("Bold", cue.text)
    }
}
