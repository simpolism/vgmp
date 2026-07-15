package org.vlessert.vgmp.playback

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.security.MessageDigest

internal object SevenZArchiveStore {
    fun list(archive: File, path: String): List<ZipArchiveStore.Item> {
        val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = linkedMapOf<String, ZipArchiveStore.Item>()
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                val clean = normalized(entry.name) ?: continue
                if (!clean.startsWith(prefix) || clean == prefix) continue
                val remainder = clean.removePrefix(prefix)
                val name = remainder.substringBefore('/')
                if (name.isEmpty()) continue
                val directory = entry.isDirectory || '/' in remainder
                val childPath = (prefix + name).trim('/')
                val previous = children[name]
                if (previous == null || directory) {
                    children[name] = ZipArchiveStore.Item(childPath, name, directory)
                }
            }
        }
        return children.values.sortedWith(
            compareBy<ZipArchiveStore.Item> { !it.directory }.thenComparator { a, b ->
                NaturalSort.compare(a.displayName, b.displayName)
            }
        )
    }

    fun extractedEntry(archive: File, requestedPath: String, cacheRoot: File): File {
        val normalized = normalized(requestedPath) ?: error("Invalid 7z entry")
        val archiveKey = archive.nameWithoutExtension
        val directory = File(cacheRoot, archiveKey).also { it.mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = normalized.substringAfterLast('.', "")
        val destination = File(directory, key + if (extension.isEmpty()) "" else ".$extension")
        if (destination.exists()) {
            destination.setLastModified(System.currentTimeMillis())
            return destination
        }
        synchronized(this) {
            if (destination.exists()) return destination
            val temp = File(directory, "$key.tmp")
            temp.delete()
            SevenZFile.builder().setFile(archive).get().use { sevenZ ->
                var found = false
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    if (normalized(entry.name) != normalized) continue
                    require(!entry.isDirectory) { "Cannot open a 7z directory" }
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = sevenZ.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    found = true
                    break
                }
                check(found) { "7z entry no longer exists" }
            }
            check(temp.renameTo(destination)) { "Could not cache 7z entry" }
        }
        return destination
    }

    private fun normalized(path: String): String? {
        val clean = path.replace('\\', '/').trimStart('/').trimEnd('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        return clean
    }
}
