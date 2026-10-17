package com.sublearn.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** A fresh video never offers a restart; a resumable one starts at the saved playhead. */
class PlaybackResumeTest {
    @Test fun firstViewingStartsAtZero() {
        assertEquals(0L, playbackResumePosition(null, 60_000L))
        assertEquals(0L, playbackResumePosition(0L, 60_000L))
    }

    @Test fun evenAShortPriorViewingCanResume() {
        assertEquals(2_000L, playbackResumePosition(2_000L, 60_000L))
        assertEquals(30_000L, playbackResumePosition(30_000L, 60_000L))
    }

    @Test fun finishedOrInvalidPositionsStartAtZero() {
        assertEquals(0L, playbackResumePosition(59_000L, 60_000L))
        assertEquals(0L, playbackResumePosition(90_000L, 60_000L))
        assertEquals(0L, playbackResumePosition(-1L, 60_000L))
        assertEquals(0L, playbackResumePosition(2_000L, 0L))
    }
}
