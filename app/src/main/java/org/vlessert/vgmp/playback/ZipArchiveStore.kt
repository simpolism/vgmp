package org.vlessert.vgmp.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class ZipArchiveStore(private val context: Context) {
    data class Item(val path: String, val displayName: String, val directory: Boolean)

    fun list(uri: Uri, path: String): List<Item> = listArchive(localArchive(uri), path)

    fun copyEntry(uri: Uri, entryPath: String, output: OutputStream) {
        copyArchiveEntry(localArchive(uri), entryPath, output)
    }

    fun <T> withEntryInputStream(uri: Uri, entryPath: String, block: (InputStream) -> T): T =
        ZipFile(localArchive(uri)).use { zip ->
            val entry = findEntry(zip, entryPath)
            require(!entry.isDirectory) { "Cannot open a ZIP directory" }
            zip.getInputStream(entry).use(block)
        }

    private fun localArchive(uri: Uri): File {
        val dir = File(context.cacheDir, "zip-archives").also { it.mkdirs() }
        val metadata = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val size = cursor.getLong(0).takeUnless { cursor.isNull(0) } ?: -1L
                    val modified = cursor.getLong(1).takeUnless { cursor.isNull(1) } ?: -1L
                    "$size:$modified"
                }
            }
        }.getOrNull() ?: "unknown"
        val name = MessageDigest.getInstance("SHA-256")
            .digest("$uri|$metadata".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val destination = File(dir, "$name.zip")
        if (!destination.exists()) synchronized(copyLock) {
            if (!destination.exists()) {
                val temp = File(dir, "$name.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open ZIP archive")
                check(temp.renameTo(destination)) { "Could not cache ZIP archive" }
                trimCache(dir, destination)
            }
        }
        destination.setLastModified(System.currentTimeMillis())
        return destination
    }

    companion object {
        private val copyLock = Any()
        private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

        fun cacheSize(context: Context): Long =
            File(context.cacheDir, "zip-archives").listFiles()?.sumOf(File::length) ?: 0L

        fun clearCache(context: Context) {
            synchronized(copyLock) {
                File(context.cacheDir, "zip-archives").listFiles()?.forEach(File::delete)
            }
        }

        private fun trimCache(dir: File, protected: File) {
            val files = dir.listFiles()?.filter { it.isFile && it.extension == "zip" }
                ?.sortedBy(File::lastModified) ?: return
            var total = files.sumOf(File::length)
            for (file in files) {
                if (total <= MAX_CACHE_BYTES) break
                if (file == protected) continue
                val length = file.length()
                if (file.delete()) total -= length
            }
        }

        internal fun listArchive(archive: File, path: String): List<Item> {
            val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val children = linkedMapOf<String, Item>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val clean = normalized(entry.name) ?: continue
                    if (!clean.startsWith(prefix) || clean == prefix) continue
                    val remainder = clean.removePrefix(prefix)
                    val name = remainder.substringBefore('/')
                    if (name.isEmpty()) continue
                    val directory = entry.isDirectory || '/' in remainder
                    val childPath = (prefix + name).trim('/')
                    val previous = children[name]
                    if (previous == null || directory) children[name] = Item(childPath, name, directory)
                }
            }
            return children.values.sortedWith(
                compareBy<Item> { !it.directory }.thenBy { it.displayName.lowercase() }
            )
        }

        internal fun copyArchiveEntry(archive: File, entryPath: String, output: OutputStream) {
            ZipFile(archive).use { zip ->
                val entry = findEntry(zip, entryPath)
                require(!entry.isDirectory) { "Cannot play a ZIP directory" }
                zip.getInputStream(entry).use { it.copyTo(output) }
            }
        }

        private fun findEntry(zip: ZipFile, entryPath: String) =
            (normalized(entryPath) ?: error("Invalid ZIP entry")).let { requested ->
                zip.getEntry(requested) ?: zip.entries().asSequence()
                    .firstOrNull { normalized(it.name) == requested }
                    ?: error("ZIP entry no longer exists")
            }

        private fun normalized(path: String): String? {
            val clean = path.replace('\\', '/').trimStart('/').trimEnd('/')
            if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
            return clean
        }
    }
}
