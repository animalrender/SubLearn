package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFormatTest {
    @Test
    fun `extension wins when it is a format we know`() {
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.fromFileName("movie.en.srt"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.fromFileName("Movie.EN.vtt"))
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.fromFileName("fansub.ass"))
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.fromFileName("fansub.ssa"))
    }

    @Test
    fun `unknown extensions come back as UNKNOWN instead of throwing`() {
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormat.fromFileName("notes.md"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormat.fromFileName("no-extension"))
    }

    @Test
    fun `mime types map onto the same formats`() {
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.fromMime("text/vtt"))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.fromMime("application/x-subrip"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormat.fromMime(null))
    }

    @Test
    fun `detection falls back to content when the name lies`() {
        assertEquals(SubtitleFormat.VTT, SubtitleParsers.detect("lie.srt", "WEBVTT\n\n00:00.000 --> 00:01.000\nhi"))
        assertEquals(SubtitleFormat.ASS, SubtitleParsers.detect("lie.srt", "[Script Info]\n[Events]\nFormat: Text\n"))
    }
}
