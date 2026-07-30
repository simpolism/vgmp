package org.vlessert.vgmp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkPaneSizeTest {
    @Test
    fun wideArtworkUsesAvailableWidthWithoutTallEmptyPane() {
        assertEquals(
            ArtworkPaneSize(width = 1_000, height = 580),
            fitArtworkPane(
                artworkWidth = 16,
                artworkHeight = 9,
                maxWidth = 1_000,
                maxHeight = 1_800,
                padding = 20
            )
        )
    }

    @Test
    fun portraitArtworkUsesAvailableHeight() {
        assertEquals(
            ArtworkPaneSize(width = 920, height = 1_800),
            fitArtworkPane(
                artworkWidth = 1,
                artworkHeight = 2,
                maxWidth = 1_000,
                maxHeight = 1_800,
                padding = 20
            )
        )
    }

    @Test
    fun invalidArtworkDimensionsRemainDisplayable() {
        assertEquals(
            ArtworkPaneSize(width = 100, height = 100),
            fitArtworkPane(
                artworkWidth = 0,
                artworkHeight = 0,
                maxWidth = 100,
                maxHeight = 100,
                padding = 0
            )
        )
    }
}
