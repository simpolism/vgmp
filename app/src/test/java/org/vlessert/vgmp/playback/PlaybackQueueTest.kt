package org.vlessert.vgmp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueTest {
    @Test
    fun replaceSelectsAndClampsStartIndex() {
        val queue = PlaybackQueue<String>()
        queue.replace(listOf("a", "b", "c"), 99)

        assertEquals("c", queue.current)
        assertEquals(2, queue.index)
    }

    @Test
    fun nextStopsAtEndUnlessWrapping() {
        val queue = PlaybackQueue<String>()
        queue.replace(listOf("a", "b"), 1)

        assertNull(queue.moveNext(wrap = false))
        assertEquals("b", queue.current)
        assertEquals("a", queue.moveNext(wrap = true))
    }

    @Test
    fun previousAtBeginningKeepsCurrentTrack() {
        val queue = PlaybackQueue<String>()
        queue.replace(listOf("a", "b"), 0)

        assertEquals("a", queue.movePrevious())
        assertEquals(0, queue.index)
    }

    @Test
    fun emptyReplacementClearsSelection() {
        val queue = PlaybackQueue<String>()
        queue.replace(listOf("a"), 0)
        queue.replace(emptyList(), 0)

        assertNull(queue.current)
        assertEquals(-1, queue.index)
    }
}
