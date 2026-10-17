package com.sublearn.feature.player

/** Do not seek beyond a finished movie; even a short prior viewing is otherwise resumable. */
internal fun playbackResumePosition(lastPositionMs: Long?, durationMs: Long): Long =
    lastPositionMs?.takeIf { it > 0L && it < durationMs - END_MARGIN_MS } ?: 0L

private const val END_MARGIN_MS = 3_000L
