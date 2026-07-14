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
}
