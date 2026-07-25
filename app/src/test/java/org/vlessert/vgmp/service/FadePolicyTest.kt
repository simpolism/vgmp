package org.vlessert.vgmp.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FadePolicyTest {
    @Test
    fun `timed fade remains full before cutoff and reaches silence at end`() {
        assertEquals(1f, timedFadeGain(sample = 999, cutoff = 1000, fadeSamples = 500))
        assertEquals(1f, timedFadeGain(sample = 1000, cutoff = 1000, fadeSamples = 500))
        assertEquals(0.5f, timedFadeGain(sample = 1250, cutoff = 1000, fadeSamples = 500))
        assertEquals(0f, timedFadeGain(sample = 1500, cutoff = 1000, fadeSamples = 500))
        assertEquals(0f, timedFadeGain(sample = 1600, cutoff = 1000, fadeSamples = 500))
    }

    @Test
    fun `disabled fade preserves full gain`() {
        assertEquals(1f, timedFadeGain(sample = 2000, cutoff = 1000, fadeSamples = 0))
    }
}
