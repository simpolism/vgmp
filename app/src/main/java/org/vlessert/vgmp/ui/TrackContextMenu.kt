package org.vlessert.vgmp.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.service.VgmPlaybackService

object TrackContextMenu {
    fun show(
        context: Context,
        track: TrackRef,
        service: VgmPlaybackService?,
        removeLabel: String? = null,
        onRemove: (() -> Unit)? = null
    ) {
        val labels = mutableListOf("Add to queue", "Play next", "Add to playlist")
        if (removeLabel != null && onRemove != null) labels += removeLabel
        AlertDialog.Builder(context)
            .setTitle(track.title)
            .setItems(labels.toTypedArray()) { _, index ->
                when (index) {
                    0 -> {
                        service?.addToQueue(track)
                        Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        service?.addToQueue(track, playNext = true)
                        Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showPlaylistPicker(context, track)
                    else -> onRemove?.invoke()
                }
            }.show()
    }

    private fun showPlaylistPicker(context: Context, track: TrackRef) {
        val playlists = PlaylistStore.getAll(context)
        val labels = (playlists.map { it.name } + "＋ New playlist").toTypedArray()
        AlertDialog.Builder(context).setTitle("Add to playlist").setItems(labels) { _, index ->
            if (index < playlists.size) {
                PlaylistStore.addTrack(context, playlists[index].id, PlaylistTrack.from(track))
                Toast.makeText(context, "Added to ${playlists[index].name}", Toast.LENGTH_SHORT).show()
            } else {
                val input = EditText(context).apply { hint = "Playlist name" }
                AlertDialog.Builder(context).setTitle("New playlist").setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            val playlist = PlaylistStore.create(context, name)
                            PlaylistStore.addTrack(context, playlist.id, PlaylistTrack.from(track))
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }
}
