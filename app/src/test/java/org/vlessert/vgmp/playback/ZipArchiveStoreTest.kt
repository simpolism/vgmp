package org.vlessert.vgmp.playback

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipArchiveStoreTest {
    @Test
    fun listsImmediateChildrenAndNestedFolders() {
        val archive = archive(
            "root.vgm" to byteArrayOf(1),
            "album/one.vgz" to byteArrayOf(2),
            "album/disc2/two.vgm" to byteArrayOf(3)
        )

        val root = ZipArchiveStore.listArchive(archive, "")
        assertEquals(listOf("album", "root.vgm"), root.map { it.displayName })
        assertTrue(root.first().directory)

        val album = ZipArchiveStore.listArchive(archive, "album")
        assertEquals(listOf("disc2", "one.vgz"), album.map { it.displayName })
        assertFalse(album.last().directory)
    }

    @Test
    fun extractsOnlyRequestedEntry() {
        val bytes = byteArrayOf(4, 5, 6)
        val archive = archive("album/song.vgm" to bytes)
        val output = ByteArrayOutputStream()

        ZipArchiveStore.copyArchiveEntry(archive, "album/song.vgm", output)

        assertArrayEquals(bytes, output.toByteArray())
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = kotlin.io.path.createTempFile(suffix = ".zip").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
