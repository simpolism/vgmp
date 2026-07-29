package org.vlessert.vgmp.service

internal fun estimatePlayedAudioFrames(
    timestampFramePosition: Long,
    timestampNanos: Long,
    nowNanos: Long,
    sampleRate: Int,
    writtenFrames: Long
): Long {
    val elapsedNanos = (nowNanos - timestampNanos).coerceAtLeast(0L)
    val extrapolatedFrames = elapsedNanos * sampleRate / 1_000_000_000L
    return (timestampFramePosition + extrapolatedFrames).coerceIn(0L, writtenFrames)
}

internal fun queuedAudioFrames(writtenFrames: Long, playedFrames: Long): Int =
    (writtenFrames - playedFrames)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
