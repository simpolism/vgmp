package org.vlessert.vgmp.playlists

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.vlessert.vgmp.playback.ArtworkRef
import org.vlessert.vgmp.playback.TrackRef
import java.util.UUID

data class PlaylistTrack(
    val uri: Uri,
    val displayName: String,
    val subtrackIndex: Int = -1,
    val archiveEntry: String? = null,
    val artworkUri: Uri? = null,
    val artworkArchiveEntry: String? = null
) {
    fun toTrackRef() = TrackRef(
        uri,
        displayName,
        subtrackIndex = subtrackIndex,
        archiveEntry = archiveEntry,
        artwork = artworkUri?.let { ArtworkRef(it, artworkArchiveEntry) }
    )

    companion object {
        fun from(track: TrackRef) = PlaylistTrack(
            track.uri,
            track.displayName,
            track.subtrackIndex,
            track.archiveEntry,
            track.artwork?.uri,
            track.artwork?.archiveEntry
        )

        fun fromJson(track: JSONObject) = PlaylistTrack(
            Uri.parse(track.getString("uri")),
            track.getString("name"),
            track.optInt("subtrackIndex", -1),
            track.optString("archiveEntry").takeIf { it.isNotEmpty() },
            track.optString("artworkUri").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            track.optString("artworkArchiveEntry").takeIf { it.isNotEmpty() }
        )
    }

    fun toJson() = JSONObject()
        .put("uri", uri.toString())
        .put("name", displayName)
        .put("subtrackIndex", subtrackIndex)
        .put("archiveEntry", archiveEntry ?: "")
        .put("artworkUri", artworkUri?.toString() ?: "")
        .put("artworkArchiveEntry", artworkArchiveEntry ?: "")
}
data class Playlist(val id: String, val name: String, val tracks: List<PlaylistTrack>)

object PlaylistStore {
    private const val PREFS = "vgmp_playlists"
    private const val KEY = "playlists"

    fun getAll(context: Context): List<Playlist> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val tracksJson = item.optJSONArray("tracks") ?: JSONArray()
                Playlist(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    tracks = List(tracksJson.length()) { trackIndex ->
                        val track = tracksJson.getJSONObject(trackIndex)
                        PlaylistTrack.fromJson(track)
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun create(context: Context, name: String): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        save(context, getAll(context) + playlist)
        return playlist
    }

    fun create(context: Context, name: String, tracks: List<PlaylistTrack>): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim(), tracks)
        save(context, getAll(context) + playlist)
        return playlist
    }

    fun replaceTracks(context: Context, playlistId: String, tracks: List<PlaylistTrack>) {
        save(context, getAll(context).map {
            if (it.id == playlistId) it.copy(tracks = tracks) else it
        })
    }

    fun addTrack(context: Context, playlistId: String, track: PlaylistTrack) {
        val updated = getAll(context).map { playlist ->
            if (playlist.id == playlistId && playlist.tracks.none {
                    it.uri == track.uri && it.archiveEntry == track.archiveEntry &&
                        it.subtrackIndex == track.subtrackIndex
                }) {
                playlist.copy(tracks = playlist.tracks + track)
            } else playlist
        }
        save(context, updated)
    }

    fun removeTrack(context: Context, playlistId: String, track: PlaylistTrack) {
        save(context, getAll(context).map { playlist ->
            if (playlist.id == playlistId) playlist.copy(tracks = playlist.tracks.filterNot {
                it.uri == track.uri && it.archiveEntry == track.archiveEntry &&
                    it.subtrackIndex == track.subtrackIndex
            })
            else playlist
        })
    }

    fun removeTrackAt(context: Context, playlistId: String, index: Int) {
        save(context, getAll(context).map { playlist ->
            if (playlist.id == playlistId && index in playlist.tracks.indices) {
                playlist.copy(tracks = playlist.tracks.toMutableList().apply { removeAt(index) })
            } else playlist
        })
    }

    fun delete(context: Context, playlistId: String) =
        save(context, getAll(context).filterNot { it.id == playlistId })

    private fun save(context: Context, playlists: List<Playlist>) {
        val json = JSONArray()
        playlists.forEach { playlist ->
            val tracks = JSONArray()
            playlist.tracks.forEach { track ->
                tracks.put(track.toJson())
            }
            json.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("tracks", tracks))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}
