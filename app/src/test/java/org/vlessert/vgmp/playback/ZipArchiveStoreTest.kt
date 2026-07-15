package org.vlessert.vgmp.playback

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
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

    @Test
    fun listsAndExtractsSevenZEntries() {
        val bytes = byteArrayOf(7, 8, 9)
        val archive = sevenZArchive(
            "album/disc2/two.vgm" to bytes,
            "album/one.vgz" to byteArrayOf(1)
        )

        val root = SevenZArchiveStore.list(archive, "")
        val album = SevenZArchiveStore.list(archive, "album")
        val extracted = SevenZArchiveStore.extractedEntry(
            archive,
            "album/disc2/two.vgm",
            kotlin.io.path.createTempDirectory().toFile()
        )

        assertEquals(listOf("album"), root.map { it.displayName })
        assertEquals(listOf("disc2", "one.vgz"), album.map { it.displayName })
        assertArrayEquals(bytes, extracted.readBytes())
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

    private fun sevenZArchive(vararg entries: Pair<String, ByteArray>): File {
        val file = kotlin.io.path.createTempFile(suffix = ".7z").toFile().apply { deleteOnExit() }
        SevenZOutputFile(file).use { sevenZ ->
            entries.forEach { (name, bytes) ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = bytes.size.toLong()
                }
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(bytes)
                sevenZ.closeArchiveEntry()
            }
        }
        return file
    }
}
