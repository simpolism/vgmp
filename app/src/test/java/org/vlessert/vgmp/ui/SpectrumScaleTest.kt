package org.vlessert.vgmp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumScaleTest {
    @Test
    fun silenceStaysAtZero() {
        assertEquals(0f, SpectrumScale.level(0f, 0.5f), 0.0001f)
    }

    @Test
    fun responseKeepsHeadroomAtMaximumInput() {
        assertTrue(SpectrumScale.level(255f, 0f) < 0.9f)
        assertTrue(SpectrumScale.level(255f, 1f) <= 0.9f)
    }

    @Test
    fun frequencyCurveCompensatesForHighFrequencyRolloff() {
        val magnitude = 40f
        assertTrue(SpectrumScale.level(magnitude, 1f) > SpectrumScale.level(magnitude, 0f))
    }
}
