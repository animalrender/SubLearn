package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PLY-8: several files in one layer.
 *
 * The merge has to keep block ids unique (the list view and the shadowing run key on them) and has
 * to answer "what is on screen" with every file that has something at that moment.
 */
class SubtitleMergeTest {
    private fun document(name: String, spans: List<Triple<Long, Long, Int>>): SubtitleDocument {
        val cues = spans.map { (start, end, id) ->
            Cue(id, start, end, "$name-$id", name)
        }
        val track = SubtitleTrack(
            id = name,
            name = name,
            languageTag = null,
            role = TrackRole.LEARNING,
            origin = TrackOrigin.EXTERNAL_SRT,
            cues = cues,
        )
        return SubtitleDocument(track, BlockBuilder().build(cues, name))
    }

    @Test
    fun `a single file is returned untouched`() {
        val only = document("a.srt", listOf(Triple(0L, 1_000L, 1)))
        assertEquals(only, SubtitleMerge.merged(TrackRole.LEARNING, listOf(only)))
    }

    @Test
    fun `no file means no document`() {
        assertNull(SubtitleMerge.merged(TrackRole.LEARNING, emptyList()))
    }

    @Test
    fun `blocks from both files are ordered by time and keep unique ids`() {
        val first = document("a.srt", listOf(Triple(0L, 1_000L, 1), Triple(2_000L, 3_000L, 2)))
        val second = document("b.srt", listOf(Triple(1_000L, 1_500L, 1), Triple(3_000L, 4_000L, 2)))
        val merged = SubtitleMerge.merged(TrackRole.LEARNING, listOf(first, second))!!
        assertEquals(4, merged.blocks.size)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), merged.blocks.map { it.startMs })
        assertEquals(4, merged.blocks.map { it.id }.distinct().size)
    }

    @Test
    fun `text at a moment joins every file that has something to say`() {
        val first = document("a.srt", listOf(Triple(0L, 2_000L, 1)))
        val second = document("b.srt", listOf(Triple(1_000L, 2_000L, 1)))
        val files = listOf(first, second)
        assertEquals("a.srt-1", SubtitleMerge.textAt(files, 500L))
        assertEquals("a.srt-1\nb.srt-1", SubtitleMerge.textAt(files, 1_500L))
        assertNull(SubtitleMerge.textAt(files, 5_000L))
    }
}
