package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssParserTest {
    private val header = """
        [Script Info]
        Title: test

        [V4+ Styles]
        Format: Name, Fontname
        Style: Default,Arial

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun `parses dialogue with override tags`() {
        val text = header + "\nDialogue: 0,0:00:01.50,0:00:04.20,Default,,0,0,0,,{\\i1}Hello {\\i0}world\n"
        val cue = AssParser.parse(text, "t").single()
        assertEquals("Hello world", cue.text)
        assertEquals(1_500L, cue.startMs)
        assertEquals(4_200L, cue.endMs)
    }

    @Test
    fun `hard breaks become newlines and commas in text survive`() {
        val text = header + "\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0000,0000,0000,,First line\\NSecond, with comma\n"
        val cue = AssParser.parse(text, "t").single()
        assertEquals("First line\nSecond, with comma", cue.text)
    }

    @Test
    fun `respects a custom format field order`() {
        val custom = """
            [Events]
            Format: Start, End, Layer, Style, Text
        """.trimIndent()
        val text = custom + "\nDialogue: 0:00:10.00,0:00:12.00,0,Default,Text comes last\n"
        val cue = AssParser.parse(text, "t").single()
        assertEquals("Text comes last", cue.text)
        assertEquals(10_000L, cue.startMs)
    }

    @Test
    fun `karaoke tags give word timing`() {
        val text = header + "\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\kf50}one {\\kf100}two\n"
        val cue = AssParser.parse(text, "t").single()
        val words = cue.tokens.filter { it.isWord }
        assertEquals(listOf("one", "two"), words.map { it.text })
        assertEquals(1_000L, words[0].startMs)
        assertEquals(1_500L, words[0].endMs)
        assertEquals(2_500L, words[1].endMs)
    }

    @Test
    fun `ignores non event sections and comment lines`() {
        val text = header + "\nComment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,hidden\n"
        assertNull(AssParser.parse(text, "t").singleOrNull())
    }

    @Test
    fun `empty dialogue text is dropped`() {
        val text = header + "\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{}\n"
        assertEquals(0, AssParser.parse(text, "t").size)
    }
}
