package org.vlessert.vgmp.playlists

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.vlessert.vgmp.playback.ArtworkLoader
import org.vlessert.vgmp.playback.ArtworkResolver
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playback.ZipArchiveStore
import java.io.ByteArrayOutputStream

object PlaylistExporter {
    fun export(context: Context, playlist: Playlist, treeUri: Uri): Int {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val width = playlist.tracks.size.toString().length.coerceAtLeast(1)
        val tracks = playlist.tracks.map(PlaylistTrack::toTrackRef)
        val artwork = tracks.map { ArtworkResolver.resolve(context, it) }
        val commonArtwork = artwork.firstOrNull()?.takeIf { candidate ->
            artwork.all { it == candidate }
        }

        if (commonArtwork != null) {
            writeArtwork(context, root, "cover.png", tracks.first())
        }

        tracks.forEachIndexed { index, track ->
            val prefix = (index + 1).toString().padStart(width, '0')
            val extension = track.displayName.substringAfterLast('.', "")
            val title = sanitize(track.title)
            val trackSuffix = if (track.subtrackIndex >= 0) " (track ${track.subtrackIndex + 1})" else ""
            val base = "$prefix - $title$trackSuffix"
            val filename = base + if (extension.isEmpty()) "" else ".$extension"
            val document = DocumentsContract.createDocument(
                resolver, root, "application/octet-stream", filename
            ) ?: error("Could not create $filename")
            resolver.openOutputStream(document, "w")!!.use { output ->
                if (track.subtrackIndex >= 0 && canPatch(track.displayName)) {
                    val bytes = readTrack(context, track)
                    patchSubtrack(bytes, extension.lowercase(), track.subtrackIndex)
                    output.write(bytes)
                } else {
                    copyTrack(context, track, output)
                }
            }
            if (commonArtwork == null && artwork[index] != null) {
                writeArtwork(context, root, "$base.png", track)
            }
        }
        return tracks.size
    }

    private fun copyTrack(context: Context, track: TrackRef, output: java.io.OutputStream) {
        if (track.archiveEntry != null) {
            ZipArchiveStore(context).copyEntry(track.uri, track.archiveEntry, output)
        } else {
            context.contentResolver.openInputStream(track.uri)?.use { it.copyTo(output) }
                ?: error("Could not open ${track.displayName}")
        }
    }

    private fun readTrack(context: Context, track: TrackRef): ByteArray =
        ByteArrayOutputStream().also { copyTrack(context, track, it) }.toByteArray()

    private fun writeArtwork(context: Context, root: Uri, name: String, track: TrackRef) {
        val ref = ArtworkResolver.resolve(context, track) ?: return
        val bitmap = ArtworkLoader.load(context, ref, 2048) ?: return
        val uri = DocumentsContract.createDocument(
            context.contentResolver, root, "image/png", name
        ) ?: return
        context.contentResolver.openOutputStream(uri, "w")?.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun canPatch(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("nsf", "gbs", "kss")

    internal fun patchSubtrack(bytes: ByteArray, extension: String, index: Int) {
        when (extension) {
            "nsf" -> if (index in 0..254 && bytes.size >= 0x80 &&
                bytes.copyOfRange(0, 5).contentEquals(byteArrayOf(0x4e, 0x45, 0x53, 0x4d, 0x1a))) {
                bytes[0x06] = 1
                bytes[0x07] = (index + 1).toByte()
            }
            "gbs" -> if (index in 0..254 && bytes.size >= 0x70 &&
                bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0x47, 0x42, 0x53))) {
                bytes[0x04] = 1
                bytes[0x05] = (index + 1).toByte()
            }
            "kss" -> if (index in 0..0xffff && bytes.size >= 0x20 &&
                bytes.copyOfRange(0, 4).contentEquals("KSSX".toByteArray()) &&
                bytes[0x0e].toInt().and(0xff) >= 0x10) {
                bytes[0x18] = (index and 0xff).toByte()
                bytes[0x19] = ((index ushr 8) and 0xff).toByte()
                bytes[0x1a] = bytes[0x18]
                bytes[0x1b] = bytes[0x19]
            }
        }
    }

    private fun sanitize(name: String): String = name
        .replace(Regex("[\\u0000-\\u001f/\\\\:*?\"<>|]"), "_")
        .trim().ifEmpty { "Track" }
}
