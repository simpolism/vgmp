package org.vlessert.vgmp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerRefreshRateTest {
    @Test
    fun capsConfiguredRateToDisplayMaximum() {
        assertEquals(120, clampVisualizerFps(240, 120))
    }

    @Test
    fun preservesRatesWithinDisplayRange() {
        assertEquals(90, clampVisualizerFps(90, 120))
    }

    @Test
    fun enforcesMinimumRate() {
        assertEquals(15, clampVisualizerFps(1, 120))
        assertEquals(15, clampVisualizerFps(60, 10))
    }

    @Test
    fun roundsSubMillisecondDeadlineWaitsUp() {
        assertEquals(1L, visualizerDelayMillis(8_000_000L, 8_333_333L))
        assertEquals(0L, visualizerDelayMillis(8_333_333L, 8_333_333L))
        assertEquals(0L, visualizerDelayMillis(8_333_333L, 0L))
    }

    @Test
    fun advancesDeadlinesWithoutDiscardingRemainder() {
        val interval = 8_333_333L
        assertEquals(interval, advanceVisualizerDeadline(0L, 0L, interval))
        assertEquals(interval * 2L, advanceVisualizerDeadline(interval, interval + 500_000L, interval))
    }

    @Test
    fun resetsDeadlineAfterAProducerStall() {
        val interval = 8_333_333L
        val now = interval * 4L
        assertEquals(now + interval, advanceVisualizerDeadline(interval, now, interval))
    }
}
