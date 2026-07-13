package org.vlessert.vgmp.playback

import android.net.Uri

/** Stable pointer to a user-owned track. No database import or filesystem scan implied. */
data class TrackRef(
    val uri: Uri,
    val displayName: String,
    val subtrackIndex: Int = -1
) {
    val title: String get() = displayName.substringBeforeLast('.', displayName)
}
