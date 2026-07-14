package org.vlessert.vgmp.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

private val artworkExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
private val conventionalArtworkNames = listOf("cover", "folder", "front", "album", "artwork")

object ArtworkResolver {
    /** Resolve art lazily too, so old playlists gain folder artwork without being recreated. */
    fun resolve(context: Context, track: TrackRef): ArtworkRef? = track.artwork
        ?: runCatching {
            if (track.archiveEntry != null) resolveArchive(context, track)
            else resolveDocument(context, track)
        }.getOrNull()

    private fun resolveArchive(context: Context, track: TrackRef): ArtworkRef? {
        val entry = track.archiveEntry ?: return null
        val folder = entry.substringBeforeLast('/', "")
        val images = ZipArchiveStore(context).list(track.uri, folder)
            .filter { !it.directory && isArtwork(it.displayName) }
        val selected = selectArtwork(track.displayName, images.map { it.displayName }) ?: return null
        return ArtworkRef(track.uri, images.first { it.displayName == selected }.path)
    }

    private fun resolveDocument(context: Context, track: TrackRef): ArtworkRef? {
        val documentId = DocumentsContract.getDocumentId(track.uri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isEmpty() || parentId == documentId) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(track.uri, parentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent)
        )
        val images = mutableListOf<Pair<String, Uri>>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1) ?: continue
                if (isArtwork(name)) {
                    images += name to DocumentsContract.buildDocumentUriUsingTree(
                        track.uri,
                        cursor.getString(0)
                    )
                }
            }
        }
        val selected = selectArtwork(track.displayName, images.map { it.first }) ?: return null
        return ArtworkRef(images.first { it.first == selected }.second)
    }
}

internal fun isArtwork(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in artworkExtensions

/** Prefer track-specific art, then conventional cover names, then the first local image. */
internal fun selectArtwork(trackName: String, imageNames: List<String>): String? {
    val trackBase = trackName.substringBeforeLast('.', trackName).lowercase()
    return imageNames.minWithOrNull(
        compareBy<String> { name ->
            val base = name.substringBeforeLast('.', name).lowercase()
            when {
                base == trackBase -> 0
                base in conventionalArtworkNames -> 1 + conventionalArtworkNames.indexOf(base)
                else -> 100
            }
        }.thenBy { it.lowercase() }
    )
}
