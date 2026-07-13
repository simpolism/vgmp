package org.vlessert.vgmp.playback

class PlaybackQueue<T> {
    var tracks: List<T> = emptyList()
        private set
    var index: Int = -1
        private set

    val current: T? get() = tracks.getOrNull(index)
    val size: Int get() = tracks.size

    fun replace(items: List<T>, startIndex: Int) {
        tracks = items
        index = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
    }

    fun moveNext(wrap: Boolean): T? {
        if (tracks.isEmpty()) return null
        if (index + 1 < tracks.size) index++
        else if (wrap) index = 0
        else return null
        return current
    }

    fun movePrevious(): T? {
        if (tracks.isEmpty()) return null
        index = if (index > 0) index - 1 else 0
        return current
    }

    fun moveRandom(): T? {
        if (tracks.isEmpty()) return null
        index = tracks.indices.random()
        return current
    }
}
