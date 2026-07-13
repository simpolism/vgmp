package org.vlessert.vgmp.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedFormatsTest {
    @Test
    fun vgmExtensionsAreCaseInsensitive() {
        assertTrue(SupportedFormats.supports("track.vgm"))
        assertTrue(SupportedFormats.supports("TRACK.VGZ"))
    }

    @Test
    fun extensionWhitespaceIsIgnored() {
        assertTrue(SupportedFormats.supports("track.vgm "))
    }

    @Test
    fun archivesRemainVisibleButAreNotPlayable() {
        assertFalse(SupportedFormats.supports("album.zip"))
    }
}
