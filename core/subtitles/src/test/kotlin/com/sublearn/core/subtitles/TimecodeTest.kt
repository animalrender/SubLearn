package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimecodeTest {
    @Test
    fun `parses srt milliseconds`() {
        assertEquals(0L, Timecode.parse("00:00:00,000"))
        assertEquals(20_400L, Timecode.parse("00:00:20,400"))
        assertEquals(3_725_123L, Timecode.parse("01:02:05.123"))
    }

    @Test
    fun `parses ass centiseconds`() {
        assertEquals(1_500L, Timecode.parse("0:00:01.50"))
        assertEquals(4_200L, Timecode.parse("0:00:04.20"))
    }

    @Test
    fun `parses short vtt mm ss mmm`() {
        assertEquals(65_500L, Timecode.parse("01:05.500"))
    }

    @Test
    fun `rejects junk`() {
        assertNull(Timecode.parse("nope"))
        assertNull(Timecode.parse(""))
    }

    @Test
    fun `splits a range and keeps vtt settings`() {
        val range = Timecode.parseRange("00:00:01,000 --> 00:00:03,500 align:start position:10%")
        assertEquals(1_000L, range?.startMs)
        assertEquals(3_500L, range?.endMs)
        assertEquals("align:start position:10%", range?.settings)
    }

    @Test
    fun `range without arrow is null`() {
        assertNull(Timecode.parseRange("00:00:01,000 00:00:02,000"))
    }
}
