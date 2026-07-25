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

    @Test
    fun sortsDigitLedHexadecimalTrackNumbersByNumericValue() {
        val names = listOf(
            "urbz-0010.minigsf",
            "urbz-000f.minigsf",
            "urbz-000a.minigsf",
            "urbz-0001.minigsf",
            "urbz-0000.minigsf"
        )
        assertEquals(
            listOf(
                "urbz-0000.minigsf",
                "urbz-0001.minigsf",
                "urbz-000a.minigsf",
                "urbz-000f.minigsf",
                "urbz-0010.minigsf"
            ),
            names.sortedWith(NaturalSort.names)
        )
    }
}
