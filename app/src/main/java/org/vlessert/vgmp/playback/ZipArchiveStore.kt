package org.vlessert.vgmp.playback

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class ZipArchiveStore(private val context: Context) {
    data class Item(val path: String, val displayName: String, val directory: Boolean)

    fun list(uri: Uri, path: String): List<Item> = listArchive(localArchive(uri), path)

    fun copyEntry(uri: Uri, entryPath: String, output: OutputStream) {
        copyArchiveEntry(localArchive(uri), entryPath, output)
    }

    private fun localArchive(uri: Uri): File {
        val dir = File(context.cacheDir, "zip-archives").also { it.mkdirs() }
        val name = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val destination = File(dir, "$name.zip")
        if (!destination.exists()) synchronized(copyLock) {
            if (!destination.exists()) {
                val temp = File(dir, "$name.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open ZIP archive")
                check(temp.renameTo(destination)) { "Could not cache ZIP archive" }
            }
        }
        return destination
    }

    companion object {
        private val copyLock = Any()

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
            val requested = normalized(entryPath) ?: error("Invalid ZIP entry")
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(requested) ?: zip.entries().asSequence()
                    .firstOrNull { normalized(it.name) == requested }
                    ?: error("ZIP entry no longer exists")
                require(!entry.isDirectory) { "Cannot play a ZIP directory" }
                zip.getInputStream(entry).use { it.copyTo(output) }
            }
        }

        private fun normalized(path: String): String? {
            val clean = path.replace('\\', '/').trimStart('/').trimEnd('/')
            if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
            return clean
        }
    }
}
