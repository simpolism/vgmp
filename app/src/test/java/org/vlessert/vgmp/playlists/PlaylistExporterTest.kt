package org.vlessert.vgmp.playlists

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistExporterTest {
    @Test
    fun patchesNsfToOneSelectedSong() {
        val bytes = ByteArray(0x80).apply {
            byteArrayOf(0x4e, 0x45, 0x53, 0x4d, 0x1a).copyInto(this)
            this[0x06] = 24
            this[0x07] = 1
        }

        PlaylistExporter.patchSubtrack(bytes, "nsf", 6)

        assertEquals(1, bytes[0x06].toInt())
        assertEquals(7, bytes[0x07].toInt())
    }

    @Test
    fun patchesGbsToOneSelectedSong() {
        val bytes = ByteArray(0x70).apply {
            byteArrayOf(0x47, 0x42, 0x53).copyInto(this)
            this[0x04] = 12
            this[0x05] = 1
        }

        PlaylistExporter.patchSubtrack(bytes, "gbs", 3)

        assertEquals(1, bytes[0x04].toInt())
        assertEquals(4, bytes[0x05].toInt())
    }

    @Test
    fun patchesKssxFirstAndLastSongAsLittleEndianWords() {
        val bytes = ByteArray(0x40).apply {
            "KSSX".toByteArray().copyInto(this)
            this[0x0e] = 0x10
        }

        PlaylistExporter.patchSubtrack(bytes, "kss", 0x123)

        assertArrayEquals(
            byteArrayOf(0x23, 0x01, 0x23, 0x01),
            bytes.copyOfRange(0x18, 0x1c)
        )
    }

    @Test
    fun leavesUnsupportedContainerUntouched() {
        val bytes = ByteArray(32) { it.toByte() }
        val original = bytes.copyOf()

        PlaylistExporter.patchSubtrack(bytes, "nsfe", 2)

        assertArrayEquals(original, bytes)
    }
}
