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

    @Test
    fun everyNativeKssExtensionUsesKssSubtrackHandling() {
        val extensions = listOf("kss", "mgs", "bgm", "opx", "mpk", "mbm")

        extensions.forEach { extension ->
            assertTrue(SupportedFormats.isKssFamily("music.$extension"))
            assertTrue(SupportedFormats.isMultiTrackContainer("music.$extension"))
        }
    }

    @Test
    fun nonKssMultitrackFormatsDoNotUseKssTrackRanges() {
        assertTrue(SupportedFormats.isMultiTrackContainer("album.nsfe"))
        assertFalse(SupportedFormats.isKssFamily("album.nsfe"))
    }

    @Test
    fun gsfAndMiniGsfUseTheGsfBackendButLibrariesAreNotPlayableEntries() {
        assertTrue(SupportedFormats.supports("music.gsf"))
        assertTrue(SupportedFormats.supports("01 Title.MINIGSF"))
        assertTrue(SupportedFormats.isGsfFamily("music.gsf"))
        assertTrue(SupportedFormats.isGsfFamily("01 Title.MINIGSF"))
        assertFalse(SupportedFormats.supports("game.gsflib"))
    }
}
