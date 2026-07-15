package org.vlessert.vgmp.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {
    @Test
    fun sortsNumericNamesAndLeadingZeros() {
        val names = listOf("Track 10", "track 2", "Track 02", "Track 1")
        assertEquals(
            listOf("Track 1", "track 2", "Track 02", "Track 10"),
            names.sortedWith(NaturalSort.names)
        )
    }
}
