package org.vlessert.vgmp.playback

import android.net.Uri

/** Pointer to artwork beside a track, either as a document or an entry in a ZIP archive. */
data class ArtworkRef(val uri: Uri, val archiveEntry: String? = null)
