package org.vlessert.vgmp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserNavigationTest {
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
