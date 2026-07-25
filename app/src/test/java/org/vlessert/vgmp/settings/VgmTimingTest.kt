package org.vlessert.vgmp.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VgmTimingTest {
    @Test
    fun `timing cycles auto to 60 to 50`() {
        assertEquals(60, nextVgmPlaybackHz(0))
        assertEquals(50, nextVgmPlaybackHz(60))
        assertEquals(0, nextVgmPlaybackHz(50))
    }

    @Test
    fun `invalid timing values normalize to auto`() {
        assertEquals(0, normalizeVgmPlaybackHz(42))
        assertEquals(60, nextVgmPlaybackHz(42))
    }

    @Test
    fun `embedded loop repeats stay within supported range`() {
        assertEquals(0, normalizeLoopRepeats(-1))
        assertEquals(1, normalizeLoopRepeats(1))
        assertEquals(10, normalizeLoopRepeats(99))
    }

    @Test
    fun `fade out length stays within supported range`() {
        assertEquals(0, normalizeFadeOutSeconds(-1))
        assertEquals(5, normalizeFadeOutSeconds(5))
        assertEquals(15, normalizeFadeOutSeconds(99))
    }
}
