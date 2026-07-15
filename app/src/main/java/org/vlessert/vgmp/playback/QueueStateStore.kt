package org.vlessert.vgmp.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vlessert.vgmp.playlists.PlaylistTrack

data class PersistedQueueState(
    val tracks: List<TrackRef>,
    val index: Int,
    val positionMs: Long,
    val shuffleMode: String,
    val loopMode: String
)

object QueueStateStore {
    private const val PREFS = "vgmp_queue_state"
    private const val KEY_QUEUE = "queue"
    private const val KEY_POSITION = "position_ms"

    fun saveQueue(
        context: Context,
        tracks: List<TrackRef>,
        index: Int,
        shuffleMode: String,
        loopMode: String
    ) {
        val items = JSONArray()
        tracks.forEach { items.put(PlaylistTrack.from(it).toJson()) }
        val root = JSONObject()
            .put("tracks", items)
            .put("index", index)
            .put("shuffle", shuffleMode)
            .put("loop", loopMode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUEUE, root.toString()).apply()
    }

    fun savePosition(context: Context, positionMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L)).apply()
    }

    fun load(context: Context): PersistedQueueState? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = JSONObject(prefs.getString(KEY_QUEUE, null) ?: return null)
        val items = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = List(items.length()) { PlaylistTrack.fromJson(items.getJSONObject(it)).toTrackRef() }
        if (tracks.isEmpty()) return null
        PersistedQueueState(
            tracks,
            root.optInt("index", 0).coerceIn(tracks.indices),
            prefs.getLong(KEY_POSITION, 0L),
            root.optString("shuffle", "OFF"),
            root.optString("loop", "OFF")
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
