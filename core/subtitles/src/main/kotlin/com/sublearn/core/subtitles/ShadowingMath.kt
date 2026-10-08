package com.sublearn.core.subtitles

/**
 * Repeat / pause maths for shadowing (SHD-1..SHD-3).
 *
 * Pure functions over a block and the user's configuration so the behaviour is unit tested without
 * a player: the player only executes the plan these functions produce.
 */
object ShadowingMath {
    /**
     * Pause between two repeats. The block's own duration is part of the formula because a learner
     * needs time proportional to what they just heard; [ShadowingPauseConfig.pauseMaxMs] keeps a
     * long paragraph from producing a minute of silence.
     */
    fun pauseMs(blockDurationMs: Long, config: ShadowingPauseConfig): Long {
        val scaled = blockDurationMs.coerceAtLeast(0L) * config.durationMultiplier
        val raw = config.baseMs + scaled.toLong()
        return raw.coerceIn(0L, maxOf(config.baseMs, config.pauseMaxMs))
    }

    /** Where a repeat starts: the block start, pulled back by the pre-roll but never negative. */
    fun repeatStart(blockStartMs: Long, config: ShadowingPauseConfig): Long =
        (blockStartMs - config.preRollMs).coerceAtLeast(0L)

    /**
     * The plan for [repeatCount] plays of one block, each followed by a pause.
     * A block shorter than [ShadowingPauseConfig.minBlockDurationMs] is not repeated at all: very
     * short interjections ("Huh?") only produce stuttering.
     */
    fun plan(block: SubtitleBlock, repeatCount: Int, config: ShadowingPauseConfig): List<RepeatSegment> {
        if (block.durationMs < config.minBlockDurationMs || repeatCount <= 0) return listOf(singlePass(block, config))
        val pause = pauseMs(block.durationMs, config)
        val start = repeatStart(block.startMs, config)
        return (0 until repeatCount).map { index ->
            RepeatSegment(
                index = index,
                startMs = if (index == 0) start else block.startMs,
                endMs = block.endMs,
                pauseAfterMs = if (index == repeatCount - 1) 0L else pause,
                isLast = index == repeatCount - 1,
            )
        }
    }

    private fun singlePass(block: SubtitleBlock, config: ShadowingPauseConfig): RepeatSegment = RepeatSegment(
        index = 0,
        startMs = block.startMs,
        endMs = block.endMs,
        pauseAfterMs = 0L,
        isLast = true,
    )

    /** True when playback reached the end of the block and must pause (SHD-3 "stop at end"). */
    fun reachedBlockEnd(block: SubtitleBlock, positionMs: Long, toleranceMs: Long = 60L): Boolean =
        positionMs >= block.endMs - toleranceMs

    /** True when the auto-repeat cycle should re-seek instead of continuing (SHD-1 hold mode). */
    fun shouldRepeatAgain(segment: RepeatSegment, positionMs: Long, toleranceMs: Long = 60L): Boolean =
        !segment.isLast && positionMs >= segment.endMs - toleranceMs

    /** How far past the pause the position is, so a laggy frame never skips a repeat. */
    fun pauseElapsed(positionMs: Long, segmentEndMs: Long, pauseMs: Long): Long =
        (positionMs - segmentEndMs).coerceIn(0L, maxOf(0L, pauseMs))

    fun isInsidePause(positionMs: Long, segment: RepeatSegment): Boolean =
        positionMs > segment.endMs && pauseElapsed(positionMs, segment.endMs, segment.pauseAfterMs) < segment.pauseAfterMs
}

/** The subset of shadowing settings these functions need; kept separate so the maths is reusable. */
data class ShadowingPauseConfig(
    val baseMs: Long = 400L,
    val durationMultiplier: Float = 0.35f,
    val pauseMaxMs: Long = 4_000L,
    val preRollMs: Long = 250L,
    val minBlockDurationMs: Long = 300L,
)

data class RepeatSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val pauseAfterMs: Long,
    val isLast: Boolean,
) {
    /** Total time this segment occupies, used by the repeat ring and by progress hints. */
    val totalMs: Long get() = (endMs - startMs).coerceAtLeast(0L) + pauseAfterMs
}
