package org.vlessert.vgmp.service

internal fun estimatePlayedAudioFrames(
    timestampFramePosition: Long,
    timestampNanos: Long,
    nowNanos: Long,
    sampleRate: Int,
    writtenFrames: Long
): Long {
    // AudioTrack may timestamp a frame that is either already presented or
    // committed to be presented in the future. Preserve the signed delta so
    // future timestamps extrapolate backwards to the frame audible now.
    val elapsedNanos = nowNanos - timestampNanos
    val extrapolatedFrames = elapsedNanos * sampleRate / 1_000_000_000L
    return (timestampFramePosition + extrapolatedFrames).coerceIn(0L, writtenFrames)
}

internal fun queuedAudioFrames(writtenFrames: Long, playedFrames: Long): Int =
    (writtenFrames - playedFrames)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
