package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDocumentTest {
    private fun block(id: Long, start: Long, end: Long, text: String) =
        SubtitleBlock(id, start, end, text, "t", listOf(Cue(id.toInt(), start, end, text, "t")))

    private val document = SubtitleDocument(
        track = SubtitleTrack("t", "English", "en", TrackRole.LEARNING, TrackOrigin.EXTERNAL_SRT, emptyList()),
        blocks = listOf(
            block(0, 1_000, 3_000, "First"),
            block(1, 3_000, 6_000, "Second"),
            block(2, 9_000, 11_000, "Third"),
        ),
    )

    @Test
    fun `lookup finds the covering block`() {
        assertEquals("First", document.blockAt(1_500)?.text)
        assertEquals("Second", document.blockAt(3_000)?.text)
        assertNull(document.blockAt(7_000))
    }

    @Test
    fun `lookup before the first block still finds it only when in range`() {
        assertNull(document.blockAt(500))
        assertEquals("First", SubtitleDocument(document.track, document.blocks).blockAt(1_000)?.text)
    }

    @Test
    fun `next and previous navigation`() {
        assertEquals("Second", document.nextBlock(1_500)?.text)
        assertEquals("Third", document.nextBlock(6_000)?.text)
        assertNull(document.nextBlock(11_000))
        assertEquals("Second", document.previousBlock(9_000)?.text)
        assertNull(document.previousBlock(1_000))
    }

    @Test
    fun `range selection is half-open`() {
        assertEquals(listOf("Second", "Third"), document.blocksInRange(5_999, 10_000).map { it.text })
    }

    @Test
    fun `context window takes the blocks before an index`() {
        assertEquals(listOf("First", "Second"), document.blocksBefore(2, 10).map { it.text })
        assertEquals(listOf("Second"), document.blocksBefore(2, 1).map { it.text })
        assertTrue(document.blocksBefore(0, 5).isEmpty())
    }

    @Test
    fun `delay shifts cues without breaking the index contract`() {
        val cues = document.blocks.map { it.cues.first() }
        val track = document.track.copy(cues = cues)
        assertEquals(1_500L, track.withDelay(500L).shiftedCues().first().startMs)
        assertEquals(1_000L, track.withDelay(0L).shiftedCues().first().startMs)
    }

    @Test
    fun `toDocument rebuilds blocks from cues`() {
        val cues = document.blocks.map { it.cues.first() }
        val rebuilt = document.track.copy(cues = cues).toDocument()
        assertTrue(rebuilt.blocks.isNotEmpty())
        assertEquals("First", rebuilt.blockAt(1_500)?.text)
    }
}
