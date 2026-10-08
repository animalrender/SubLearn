package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowingMathTest {
    private val config = ShadowingPauseConfig(
        baseMs = 500L,
        durationMultiplier = 0.5f,
        pauseMaxMs = 2_000L,
        preRollMs = 200L,
        minBlockDurationMs = 300L,
    )

    private fun block(start: Long, end: Long) = SubtitleBlock(0, start, end, "text", "t", emptyList())

    @Test
    fun `pause scales with duration and clamps to the max`() {
        assertEquals(1_000L, ShadowingMath.pauseMs(1_000L, config))
        assertEquals(1_500L, ShadowingMath.pauseMs(2_000L, config))
        assertEquals(2_000L, ShadowingMath.pauseMs(60_000L, config))
        assertEquals(500L, ShadowingMath.pauseMs(0L, config))
    }

    @Test
    fun `pause never shrinks below the base even with a tiny max`() {
        val tight = config.copy(pauseMaxMs = 100L)
        assertEquals(500L, ShadowingMath.pauseMs(5_000L, tight))
    }

    @Test
    fun `pre roll moves the first pass back but not below zero`() {
        assertEquals(800L, ShadowingMath.repeatStart(1_000L, config))
        assertEquals(0L, ShadowingMath.repeatStart(100L, config))
    }

    @Test
    fun `plan repeats n times with pauses between and none after the last`() {
        val plan = ShadowingMath.plan(block(1_000, 3_000), repeatCount = 3, config)
        assertEquals(3, plan.size)
        assertEquals(listOf(1_500L, 1_500L, 0L), listOf(plan[0].pauseAfterMs, plan[1].pauseAfterMs, plan[2].pauseAfterMs))
        assertEquals(800L, plan[0].startMs)
        assertEquals(1_000L, plan[1].startMs)
        assertTrue(plan.last().isLast)
    }

    @Test
    fun `a very short block is played once only`() {
        val plan = ShadowingMath.plan(block(1_000, 1_100), repeatCount = 4, config)
        assertEquals(1, plan.size)
        assertTrue(plan[0].isLast)
    }

    @Test
    fun `zero repeats still produce a single pass`() {
        assertEquals(1, ShadowingMath.plan(block(0, 2_000), 0, config).size)
    }

    @Test
    fun `end detection uses a tolerance so a late frame still triggers`() {
        val block = block(1_000, 3_000)
        assertTrue(ShadowingMath.reachedBlockEnd(block, 2_960L))
        assertTrue(!ShadowingMath.reachedBlockEnd(block, 2_500L))
    }

    @Test
    fun `pause window is inclusive and bounded`() {
        val segment = ShadowingMath.plan(block(1_000, 3_000), 2, config).first()
        assertTrue(ShadowingMath.isInsidePause(3_100L, segment))
        assertTrue(!ShadowingMath.isInsidePause(4_600L, segment))
        assertTrue(!ShadowingMath.isInsidePause(2_900L, segment))
        assertEquals(1_500L, segment.pauseAfterMs)
    }
}
