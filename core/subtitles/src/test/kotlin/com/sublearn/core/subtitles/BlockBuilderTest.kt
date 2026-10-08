package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockBuilderTest {
    private val config = NormalizerConfig(mergeGapMs = 700, maxBlockChars = 84, maxBlockDurationMs = 7_000)

    @Test
    fun `merges cues that are split mid-sentence`() {
        val cues = listOf(
            Cue(1, 0, 2_000, "When you", "t"),
            Cue(2, 2_100, 4_000, "have to go", "t"),
        )
        val blocks = BlockBuilder(config).build(cues, "t")
        assertEquals(1, blocks.size)
        assertEquals("When you have to go", blocks[0].text)
        assertEquals(0L, blocks[0].startMs)
        assertEquals(4_000L, blocks[0].endMs)
    }

    @Test
    fun `a long gap starts a new block`() {
        val cues = listOf(
            Cue(1, 0, 2_000, "Done.", "t"),
            Cue(2, 9_000, 11_000, "New scene.", "t"),
        )
        assertEquals(2, BlockBuilder(config).build(cues, "t").size)
    }

    @Test
    fun `an over-long run is cut to the character limit`() {
        val cue = Cue(1, 0, 20_000, "word ".repeat(60).trim(), "t")
        val blocks = BlockBuilder(NormalizerConfig(maxBlockChars = 40)).build(listOf(cue), "t")
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length <= 40 })
    }

    @Test
    fun `merged blocks keep word timings inside the merged text`() {
        val cues = listOf(
            Cue(1, 0, 1_000, "alpha", "t", tokens = listOf(CueToken("alpha", 0, 5, 0, 1_000))),
            Cue(2, 1_100, 2_000, "beta", "t", tokens = listOf(CueToken("beta", 0, 4, 1_100, 2_000))),
        )
        val block = BlockBuilder(config).build(cues, "t").single()
        assertEquals("alpha beta", block.text)
        val words = block.tokens.filter { it.isWord }
        assertEquals(listOf("alpha", "beta"), words.map { it.text })
        assertEquals(6, words[1].charStart)
        assertEquals(block.text.substring(words[1].charStart, words[1].charEnd), words[1].text)
    }

    @Test
    fun `block ids are dense and ordered`() {
        val cues = (0 until 20).map { Cue(it + 1, it * 10_000L, it * 10_000L + 1_000, "cue $it.", "t") }
        val blocks = BlockBuilder(config).build(cues, "t")
        assertEquals(blocks.indices.map { it.toLong() }, blocks.map { it.id })
        assertTrue(blocks.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `empty input gives no blocks`() {
        assertTrue(BlockBuilder(config).build(emptyList(), "t").isEmpty())
    }
}
