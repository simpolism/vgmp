package org.vlessert.vgmp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserNavigationTest {

    @Test
    fun `artwork prefers track basename then conventional cover names`() {
        val images = listOf("scan.png", "folder.jpg", "Battle Theme.webp")
        assertEquals("Battle Theme.webp", selectArtwork("Battle Theme.vgz", images))
        assertEquals("folder.jpg", selectArtwork("Overworld.vgm", images))
    }

    @Test
    fun `artwork falls back deterministically to any image`() {
        assertEquals("a.png", selectArtwork("track.vgm", listOf("z.jpg", "a.png")))
        assertEquals(null, selectArtwork("track.vgm", emptyList()))
    }
    @Test
    fun derivesParentWithinSelectedTree() {
        assertEquals(
            "primary:Music/VGM/nes",
            parentDocumentId("primary:Music", "primary:Music/VGM/nes/NSF")
        )
    }

    @Test
    fun opaqueOrOutsideIdsFallBackToRoot() {
        assertEquals("primary:Music", parentDocumentId("primary:Music", "opaque-id"))
        assertEquals("primary:Music", parentDocumentId("primary:Music", "primary:Other/file"))
    }
}
