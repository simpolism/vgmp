package org.vlessert.vgmp.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackPositionTest {
    @Test
    fun extrapolatesHardwareTimestampToCurrentTime() {
        assertEquals(
            1_441L,
            estimatePlayedAudioFrames(
                timestampFramePosition = 1_000L,
                timestampNanos = 2_000_000_000L,
                nowNanos = 2_010_000_000L,
                sampleRate = 44_100,
                writtenFrames = 4_000L
            )
        )
    }

    @Test
    fun clampsPlaybackEstimateToGeneratedAudio() {
        assertEquals(
            1_200L,
            estimatePlayedAudioFrames(
                timestampFramePosition = 1_000L,
                timestampNanos = 0L,
                nowNanos = 1_000_000_000L,
                sampleRate = 44_100,
                writtenFrames = 1_200L
            )
        )
    }

    @Test
    fun extrapolatesBackwardsFromFuturePresentationTimestamp() {
        assertEquals(
            1_559L,
            estimatePlayedAudioFrames(
                timestampFramePosition = 2_000L,
                timestampNanos = 2_010_000_000L,
                nowNanos = 2_000_000_000L,
                sampleRate = 44_100,
                writtenFrames = 4_000L
            )
        )
    }

    @Test
    fun computesNonNegativeQueuedFrames() {
        assertEquals(6_000, queuedAudioFrames(10_000L, 4_000L))
        assertEquals(0, queuedAudioFrames(4_000L, 10_000L))
    }
}
