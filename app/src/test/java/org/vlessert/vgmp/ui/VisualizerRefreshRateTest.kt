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
}
