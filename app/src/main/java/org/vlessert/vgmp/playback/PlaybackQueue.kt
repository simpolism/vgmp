package org.vlessert.vgmp.playback

class PlaybackQueue<T> {
    var tracks: List<T> = emptyList()
        private set
    var index: Int = -1
        private set

    val current: T? get() = tracks.getOrNull(index)
    val size: Int get() = tracks.size
    private val shuffleHistory = ArrayDeque<Int>()

    fun replace(items: List<T>, startIndex: Int) {
        tracks = items
        index = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
        shuffleHistory.clear()
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
        if (tracks.size == 1) return current
        if (index in tracks.indices) shuffleHistory.addLast(index)
        index = tracks.indices.filter { it != index }.random()
        return current
    }

    fun movePreviousRandom(): T? {
        if (tracks.isEmpty()) return null
        val previous = shuffleHistory.removeLastOrNull() ?: return current
        index = previous.coerceIn(tracks.indices)
        return current
    }

    fun add(item: T) {
        tracks = tracks + item
        if (index < 0) index = 0
    }

    fun insertNext(item: T) {
        val insertion = if (index in tracks.indices) index + 1 else tracks.size
        tracks = tracks.toMutableList().apply { add(insertion, item) }
        if (index < 0) index = 0
    }

    data class Removal<T>(val removed: T, val removedCurrent: Boolean, val newCurrent: T?)

    fun removeAt(position: Int): Removal<T>? {
        if (position !in tracks.indices) return null
        val oldIndex = index
        val mutable = tracks.toMutableList()
        val removed = mutable.removeAt(position)
        tracks = mutable
        index = when {
            tracks.isEmpty() -> -1
            position < oldIndex -> oldIndex - 1
            position == oldIndex -> oldIndex.coerceAtMost(tracks.lastIndex)
            else -> oldIndex
        }
        shuffleHistory.clear()
        return Removal(removed, position == oldIndex, current)
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return false
        val oldIndex = index
        val mutable = tracks.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        tracks = mutable
        index = when {
            from == oldIndex -> to
            from < oldIndex && to >= oldIndex -> oldIndex - 1
            from > oldIndex && to <= oldIndex -> oldIndex + 1
            else -> oldIndex
        }
        shuffleHistory.clear()
        return true
    }
}
