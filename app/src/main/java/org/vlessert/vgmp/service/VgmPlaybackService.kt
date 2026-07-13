package org.vlessert.vgmp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.VgmServiceBinder
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.engine.VgmTags
import org.vlessert.vgmp.playback.PlaybackQueue
import org.vlessert.vgmp.playback.SupportedFormats
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playback.ZipArchiveStore
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.settings.SettingsManager
import java.io.File
import java.io.IOException
import android.widget.Toast

class VgmPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val NOTIF_CHANNEL_ID = "vgmp_playback"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 44100
        const val BUFFER_FRAMES = 1024
        const val ACTION_PLAY   = "org.vlessert.vgmp.ACTION_PLAY"
        const val ACTION_PAUSE  = "org.vlessert.vgmp.ACTION_PAUSE"
        const val ACTION_NEXT   = "org.vlessert.vgmp.ACTION_NEXT"
        const val ACTION_PREV   = "org.vlessert.vgmp.ACTION_PREV"
        const val ACTION_STOP   = "org.vlessert.vgmp.ACTION_STOP"
        const val MEDIA_ID_ROOT = "root"
        private const val TAG = "VgmPlaybackService"
        private const val FADE_MS = 2000L
    }

    enum class ShuffleMode { OFF, ON }
    enum class LoopMode { OFF, TRACK, QUEUE }

    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val queue = PlaybackQueue<TrackRef>()
    private var currentLocalPath: String? = null
    private var isPlaying = false
    private var isPaused  = false
    private var shouldPlayAfterFocusGain = false
    private var shuffleMode = ShuffleMode.OFF
    private var loopMode = LoopMode.OFF
    private var currentTags = VgmTags()
    private var trackDurationMs = 0L

    // For fade out
    private var fadeStartTimeMs = 0L
    private var isFadingOut = false
    private var currentVolume = 1.0f
    
    // Endless loop mode
    private var endlessLoopMode = false

    // Render thread
    private val _spectrum = MutableStateFlow(FloatArray(512))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val spectrumBuffer = FloatArray(512)
    
    // Channel spectrums thread
    private val _channelSpectrums = MutableStateFlow<FloatArray?>(null)
    val channelSpectrums: StateFlow<FloatArray?> = _channelSpectrums.asStateFlow()
    
    private var lastSpectrumUpdateMs = 0L

    private var renderJob: Job? = null
    private val renderBuffer = ShortArray(BUFFER_FRAMES * 2)  // interleaved stereo

    private val _playbackState = MutableStateFlow<PlaybackInfo>(PlaybackInfo())
    val playbackInfo = _playbackState.asStateFlow()

    data class PlaybackInfo(
        val playing: Boolean = false,
        val paused: Boolean = false,
        val queueIndex: Int = -1,
        val track: TrackRef? = null,
        val durationMs: Long = 0L,
        val endlessLoop: Boolean = false
    )

    // Position tracking
    private var playbackStartTimeMs = 0L
    private var pausedPositionMs    = 0L

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        setupMediaSession()
        
        // Fix for "Context.startForegroundService() did not then call Service.startForeground()"
        // We must call startForeground immediately upon creation when started as a foreground service.
        startForeground(NOTIF_ID, buildNotification(false))

        VgmEngine.nSetSampleRate(SAMPLE_RATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Initialize the engine and the two ROM resources used by supported formats.
        serviceScope.launch {
            VgmEngine.setSampleRate(SAMPLE_RATE) // Use thread-safe version
            extractRoms()
            VgmEngine.setBassEnabled(SettingsManager.isBassEnabled(applicationContext))
            VgmEngine.setReverbEnabled(SettingsManager.isReverbEnabled(applicationContext))
        }
    }

    private suspend fun extractRoms() = withContext(Dispatchers.IO) {
        val romsDir = File(filesDir, "roms").also { it.mkdirs() }
        
        // Extract yrw801.rom for libvgm
        val romFileName = "yrw801.rom"
        val destFile = File(romsDir, romFileName)
        if (!destFile.exists()) {
            try {
                assets.open(romFileName).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $romFileName", e)
            }
        }
        
        // Extract GENMIDI.lmp for libMusDoom (Doom MUS playback)
        val genmidiFileName = "GENMIDI.lmp"
        val genmidiDestFile = File(romsDir, genmidiFileName)
        if (!genmidiDestFile.exists()) {
            try {
                assets.open(genmidiFileName).use { input ->
                    genmidiDestFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $genmidiFileName", e)
            }
        }
        
        VgmEngine.setRomPath(romsDir.absolutePath)
    }

    private fun setupMediaSession() {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "VgmPlayback").apply {
            setSessionActivity(pendingIntent)
            setCallback(sessionCallback)
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { resumeOrPlay() }
        override fun onPause() { pausePlayback() }
        override fun onStop() { stopPlayback() }
        override fun onSkipToNext() { nextTrack() }
        override fun onSkipToPrevious() { previousTrack() }
        override fun onSeekTo(pos: Long) { seekTo(pos) }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            val parts = mediaId.split("/")
            if (parts.size != 3 || parts[0] != "track") return
            val playlist = PlaylistStore.getAll(applicationContext).firstOrNull { it.id == parts[1] } ?: return
            val index = parts[2].toIntOrNull() ?: return
            playQueue(
                playlist.tracks.map { TrackRef(it.uri, it.displayName, archiveEntry = it.archiveEntry) },
                index
            )
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            loopMode = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> LoopMode.TRACK
                PlaybackStateCompat.REPEAT_MODE_ALL -> LoopMode.QUEUE
                else -> LoopMode.OFF
            }
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            this@VgmPlaybackService.shuffleMode = when(shuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL,
                PlaybackStateCompat.SHUFFLE_MODE_GROUP -> ShuffleMode.ON
                else -> ShuffleMode.OFF
            }
        }
    }

    // ------- Audio focus setup -------
    
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pendingPlayback: Pair<TrackRef, String>? = null
    private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioTrack?.setVolume(1.0f)
                if (shouldPlayAfterFocusGain) resumeOrPlay()
                // Handle delayed focus gain for pending playback
                pendingPlayback?.let { (track, path) ->
                    pendingPlayback = null
                    serviceScope.launch { startTrackWithFocus(track, path) }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                shouldPlayAfterFocusGain = false
                pendingPlayback = null
                pausePlayback()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                shouldPlayAfterFocusGain = isPlaying && !isPaused
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioTrack?.setVolume(0.2f)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || 
                   result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    // ------- Audio setup -------

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, BUFFER_FRAMES * 2 * 2) // frames * channels * bytes/sample
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ------- Playback control -------

    fun playQueue(tracks: List<TrackRef>, startIndex: Int = 0) {
        val playable = tracks.filter { isSupportedDocument(it.displayName) }
        queue.replace(playable, startIndex)
        if (queue.current == null) return
        serviceScope.launch { startCurrentTrack() }
    }

    fun playDocument(uri: Uri, displayName: String) = playQueue(listOf(TrackRef(uri, displayName)))

    private suspend fun startCurrentTrack() {
        val track = queue.current ?: return
        val path = materialize(track) ?: return
        currentLocalPath = path
        startTrack(track, path)
    }

    private suspend fun materialize(track: TrackRef): String? {
        if (!SupportedFormats.supports(track.displayName)) return null
        val directPlayDir = File(filesDir, "direct-play").also { it.mkdirs() }
        val safeName = track.displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .takeLast(180).ifEmpty { "track" }
        val destination = File(directPlayDir, "current-$safeName")
        return try {
            withContext(Dispatchers.IO) {
                directPlayDir.listFiles()?.filter { it != destination }?.forEach { it.delete() }
                destination.outputStream().use { output ->
                    if (track.archiveEntry != null) {
                        ZipArchiveStore(applicationContext).copyEntry(track.uri, track.archiveEntry, output)
                    } else {
                        contentResolver.openInputStream(track.uri)?.use { input -> input.copyTo(output) }
                            ?: throw IOException("Could not open the selected document")
                    }
                }
            }
            destination.absolutePath
        } catch (e: Exception) {
            destination.delete()
            Log.e(TAG, "Failed to copy ${track.displayName}", e)
            Toast.makeText(applicationContext, "Could not open ${track.displayName}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private suspend fun startTrack(track: TrackRef, path: String) {
        stopRenderJob()
        
        // Request audio focus - handle delayed focus for Android Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                Log.e(TAG, "Audio focus request failed")
                return
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                pendingPlayback = track to path
                return
            }
            hasAudioFocus = true
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.e(TAG, "Failed to get audio focus")
                return
            }
            hasAudioFocus = true
        }
        
        startTrackWithFocus(track, path)
    }

    private suspend fun startTrackWithFocus(track: TrackRef, path: String) {
        val opened = VgmEngine.open(path)
        if (!opened) {
            Log.e(TAG, "Failed to open $path")
            isPlaying = false
            isPaused = false
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            _playbackState.value = PlaybackInfo()
            abandonAudioFocus()
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Unable to play ${track.displayName}", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // Show toast for PSF files since they require background generation
        val lowerPath = path.lowercase()
        if (lowerPath.endsWith(".psf") || lowerPath.endsWith(".psf1")) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Generating audio, please wait...", Toast.LENGTH_LONG).show()
            }
        }
        
        // For multi-track files (NSF, GBS, etc.), switch to the correct sub-track
        if (track.subtrackIndex >= 0) {
            val switched = VgmEngine.setTrack(track.subtrackIndex)
            if (!switched) {
                Log.e(TAG, "Failed to set sub-track ${track.subtrackIndex}")
            }
        }

        // Reset endless loop mode when starting a new track
        if (endlessLoopMode) {
            endlessLoopMode = false
            VgmEngine.setEndlessLoop(false)
        }

        // Parse tags from VGM file
        val rawTags = VgmEngine.getTags()
        val parsedTags = VgmEngine.parseTags(rawTags)

        // Device volume profiles are global per chip name, not per track.
        for (i in 0 until VgmEngine.getDeviceCount()) {
            val chipName = VgmEngine.getDeviceName(i)
            val current = VgmEngine.getDeviceVolume(i)
            VgmEngine.setDeviceVolume(i, SettingsManager.getChipVolume(applicationContext, chipName, current))
        }
        
        currentTags = VgmTags(
            trackEn = parsedTags.trackEn.ifEmpty { track.title },
            trackJp = parsedTags.trackJp,
            gameEn = parsedTags.gameEn,
            gameJp = parsedTags.gameJp,
            systemEn = parsedTags.systemEn,
            systemJp = parsedTags.systemJp,
            authorEn = parsedTags.authorEn,
            authorJp = parsedTags.authorJp,
            date = parsedTags.date,
            creator = parsedTags.creator,
            notes = parsedTags.notes
        )
        
        val liveDurationSamples = VgmEngine.getTotalSamples()
        trackDurationMs = if (liveDurationSamples > 0) liveDurationSamples * 1000L / SAMPLE_RATE else 0L

        // Update MediaSession metadata (→ AVRCP 1.6)
        updateMediaSessionMetadata()

        // Start audio track and render loop
        isPlaying = true
        isPaused  = false
        playbackStartTimeMs = SystemClock.elapsedRealtime()
        pausedPositionMs    = 0L

        audioTrack?.release()
        audioTrack = createAudioTrack().also { it.play() }

        startRenderJob()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForeground(NOTIF_ID, buildNotification(true))
        _playbackState.value = PlaybackInfo(true, false, queue.index, track, trackDurationMs)
    }

    // Position update tracking
    private var lastPositionUpdateMs = 0L
    private val POSITION_UPDATE_INTERVAL_MS = 500L

    private fun startRenderJob() {
        renderJob = serviceScope.launch(Dispatchers.IO) {
            try {
                while (isActive && isPlaying) {
                    if (isPaused) {
                        delay(50)
                        continue
                    }
                    val framesWritten = VgmEngine.fillBuffer(renderBuffer, BUFFER_FRAMES)
                    if (framesWritten > 0) {
                        applyVolumeAndFade(renderBuffer, framesWritten)
                        audioTrack?.write(renderBuffer, 0, framesWritten * 2)

                        // Update spectrum for UI
                        val nowSpectrum = SystemClock.elapsedRealtime()
                        val spectrumIntervalMs = 1000L / SettingsManager.getVisualizerFps(applicationContext)
                        if (nowSpectrum - lastSpectrumUpdateMs >= spectrumIntervalMs) {
                            lastSpectrumUpdateMs = nowSpectrum
                            VgmEngine.getSpectrum(spectrumBuffer)
                            _spectrum.emit(spectrumBuffer.copyOf())
                            // Channel levels for KSS
                            val trackPath = currentLocalPath.orEmpty()
                            if (trackPath.endsWith(".kss", ignoreCase = true) ||
                                trackPath.endsWith(".mgs", ignoreCase = true)) {
                                val spectrums = VgmEngine.getChannelSpectrums()
                                _channelSpectrums.emit(spectrums)
                            } else {
                                _channelSpectrums.emit(null)
                            }
                        }
                    } else {
                        // fillBuffer returned 0 — PSF generation thread hasn't caught up yet.
                        // Yield CPU for a short time so the generation thread can make progress
                        // instead of busy-spinning and starving it, which causes AudioTrack underruns.
                        delay(5)
                    }
                    
                    // Periodically update playback state for position tracking (must be BEFORE endless loop check)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPositionUpdateMs >= POSITION_UPDATE_INTERVAL_MS) {
                        lastPositionUpdateMs = now
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                    
                    // Skip fade out and track end detection in endless loop mode
                    if (endlessLoopMode) continue
                    
                    // Trigger fade out before actual end if we know the duration
                    val pos = currentPositionMs()
                    if (trackDurationMs > 0 && pos >= trackDurationMs - FADE_MS && !isFadingOut) {
                        startFadeOut()
                    }

                    // Check if track ended
                    if (VgmEngine.isEnded() || (isFadingOut && SystemClock.elapsedRealtime() >= fadeStartTimeMs + FADE_MS)) {
                        serviceScope.launch { onTrackEnded() }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Render loop error", e)
            }
        }
    }

    private fun startFadeOut() {
        if (isFadingOut) return
        isFadingOut = true
        fadeStartTimeMs = SystemClock.elapsedRealtime()
    }

    private fun applyVolumeAndFade(buffer: ShortArray, frames: Int) {
        if (!isFadingOut) return
        
        val elapsed = SystemClock.elapsedRealtime() - fadeStartTimeMs
        val fadeFactor = (1.0f - (elapsed.toFloat() / FADE_MS)).coerceIn(0f, 100f)
        
        for (i in 0 until (frames * 2)) {
            buffer[i] = (buffer[i] * fadeFactor).toInt().toShort()
        }
    }

    private fun stopRenderJob() {
        renderJob?.cancel()
        renderJob = null
        isFadingOut = false
        audioTrack?.pause()
        audioTrack?.flush()
    }

    private suspend fun onTrackEnded() {
        when (loopMode) {
            LoopMode.TRACK -> startCurrentTrack()
            LoopMode.QUEUE -> {
                if (queue.moveNext(wrap = true) != null) startCurrentTrack() else stopPlayback()
            }
            LoopMode.OFF -> {
                if (queue.moveNext(wrap = false) != null) startCurrentTrack() else stopPlayback()
            }
        }
    }

    private fun resumeOrPlay() {
        if (!isPlaying) {
            if (queue.current != null) serviceScope.launch { startCurrentTrack() }
            return
        }
        if (isPaused) {
            isPaused = false
            playbackStartTimeMs = SystemClock.elapsedRealtime() - pausedPositionMs
            audioTrack?.play()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification(true)
            _playbackState.value = _playbackState.value.copy(paused = false, playing = true)
        }
    }

    private fun pausePlayback() {
        if (!isPlaying || isPaused) return
        isPaused = true
        pausedPositionMs = SystemClock.elapsedRealtime() - playbackStartTimeMs
        audioTrack?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification(false)
        _playbackState.value = _playbackState.value.copy(paused = true, playing = true)
    }

    private fun stopPlayback() {
        isPlaying = false
        isPaused = false
        stopRenderJob()
        serviceScope.launch {
            VgmEngine.stop()
            VgmEngine.close()
        }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        abandonAudioFocus()
        _playbackState.value = PlaybackInfo()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun nextTrack() {
        serviceScope.launch {
            if (!isFadingOut) {
                startFadeOut()
                delay(500)
            }
            val moved = if (shuffleMode == ShuffleMode.OFF) {
                queue.moveNext(wrap = loopMode == LoopMode.QUEUE)
            } else {
                queue.moveRandom()
            }
            if (moved != null) startCurrentTrack()
        }
    }

    fun previousTrack() {
        serviceScope.launch {
            if (queue.movePrevious() != null) startCurrentTrack()
        }
    }
    private fun seekTo(posMs: Long) {
        val samplePos = posMs * SAMPLE_RATE / 1000L
        serviceScope.launch { VgmEngine.seek(samplePos) }
        pausedPositionMs = posMs
        playbackStartTimeMs = SystemClock.elapsedRealtime() - posMs
        updatePlaybackState(if (isPaused) PlaybackStateCompat.STATE_PAUSED
                            else PlaybackStateCompat.STATE_PLAYING)
    }

    // ------- Playback state / notification -------

    private fun currentPositionMs(): Long {
        return if (isPaused) pausedPositionMs
        else SystemClock.elapsedRealtime() - playbackStartTimeMs
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, currentPositionMs(), 1f, SystemClock.elapsedRealtime())
                .setActions(actions)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "VGMP Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VGM music playback controls"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(playing: Boolean): Notification {
        val prevIntent = PendingIntent.getService(this, 1,
            Intent(ACTION_PREV).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playPauseIntent = PendingIntent.getService(this, 2,
            Intent(if (playing) ACTION_PAUSE else ACTION_PLAY).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 3,
            Intent(ACTION_NEXT).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 4,
            Intent(ACTION_STOP).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val largeIcon = getFallbackArt()

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(largeIcon)
            .setContentTitle(currentTags.displayTitle.ifEmpty { currentTrack?.title ?: "VGMP" })
            .setContentText(currentTags.displayGame)
            .setSubText(currentTags.displaySystem)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(playing)
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(playing))
    }

    // ------- MediaBrowserServiceCompat (Android Auto) -------

    override fun onBind(intent: Intent?): IBinder? {
        if (SERVICE_INTERFACE == intent?.action) {
            return super.onBind(intent)
        }
        return VgmServiceBinder(this)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(MEDIA_ID_ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val playlists = PlaylistStore.getAll(applicationContext)
        if (parentId == MEDIA_ID_ROOT) {
            playlists.forEach { playlist ->
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId("playlist/${playlist.id}")
                    .setTitle(playlist.name)
                    .setSubtitle("${playlist.tracks.size} tracks")
                    .setIconBitmap(getScaledArtForAuto())
                    .build()
                items += MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
            }
        } else if (parentId.startsWith("playlist/")) {
            val playlistId = parentId.removePrefix("playlist/")
            playlists.firstOrNull { it.id == playlistId }?.tracks?.forEachIndexed { index, track ->
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId("track/$playlistId/$index")
                    .setTitle(track.displayName.substringBeforeLast('.', track.displayName))
                    .setIconBitmap(getScaledArtForAuto())
                    .build()
                items += MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            }
        }
        result.sendResult(items)
    }
    // Get scaled art for Android Auto (256x256 is recommended size)
    private fun getScaledArtForAuto(): Bitmap? {
        val sourceBitmap = getFallbackArt() ?: return null

        // Crop to square first (only if not already square)
        val squareBitmap = if (sourceBitmap.width != sourceBitmap.height) {
            val size = minOf(sourceBitmap.width, sourceBitmap.height)
            val x = (sourceBitmap.width - size) / 2
            val y = (sourceBitmap.height - size) / 2
            Bitmap.createBitmap(sourceBitmap, x, y, size, size)
        } else {
            sourceBitmap
        }

        // Scale to 256x256 for Android Auto
        return Bitmap.createScaledBitmap(squareBitmap, 256, 256, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY   -> sessionCallback.onPlay()
            ACTION_PAUSE  -> sessionCallback.onPause()
            ACTION_NEXT   -> sessionCallback.onSkipToNext()
            ACTION_PREV   -> sessionCallback.onSkipToPrevious()
            ACTION_STOP   -> { sessionCallback.onStop(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        mediaSession.release()
        serviceScope.cancel()
    }

    // --- Expose state to bound activities ---
    val currentTrack: TrackRef? get() = queue.current
    val playing: Boolean get() = isPlaying && !isPaused
    val paused: Boolean get() = isPlaying && isPaused
    fun getMediaSession() = mediaSession

    fun isSupportedDocument(displayName: String): Boolean =
        SupportedFormats.supports(displayName)
    // --- Fallback album art for Android Auto / media display ---
    private var fallbackArtBitmap: Bitmap? = null
    
    private fun getFallbackArt(): Bitmap? {
        if (fallbackArtBitmap == null) {
            try {
                fallbackArtBitmap = BitmapFactory.decodeResource(resources, R.drawable.vgmp_logo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load fallback art", e)
            }
        }
        return fallbackArtBitmap
    }
    
    fun setShuffle(mode: ShuffleMode) { shuffleMode = mode }
    fun getShuffle(): ShuffleMode = shuffleMode
    fun setLoop(mode: LoopMode) { loopMode = mode }
    fun getLoop(): LoopMode = loopMode
    
    // Legacy boolean setter for compatibility
    fun setLoopEnabled(enabled: Boolean) { 
        loopMode = if (enabled) LoopMode.TRACK else LoopMode.OFF 
    }
    fun isLoopEnabled(): Boolean = loopMode != LoopMode.OFF
    
    // Endless loop mode - track plays forever without ending
    fun setEndlessLoop(enabled: Boolean) {
        endlessLoopMode = enabled
        serviceScope.launch(Dispatchers.IO) {
            VgmEngine.setEndlessLoop(enabled)
        }
        _playbackState.value = _playbackState.value.copy(endlessLoop = enabled)
        // Update MediaSession metadata to show/hide progress bar in notification
        updateMediaSessionMetadata()
        // Rebuild notification to reflect the change
        updateNotification(isPlaying && !isPaused)
    }
    
    private fun updateMediaSessionMetadata() {
        val track = currentTrack ?: return
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTags.displayTitle.ifEmpty { track.title })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentTags.displayAuthor)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentTags.displayGame)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentTags.displaySystem)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (queue.index + 1).toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, queue.size.toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (endlessLoopMode) 0L else trackDurationMs)
        getFallbackArt()?.let { metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession.setMetadata(metaBuilder.build())
    }
    fun getEndlessLoop(): Boolean = endlessLoopMode
    
    /**
     * Get the current track's tags (with fallback to database values).
     */
    fun getCurrentTags(): VgmTags = currentTags
    
    /**
     * Get current playback position in milliseconds.
     * This directly calculates the position rather than relying on MediaSession's static position.
     */
    fun getCurrentPositionMs(): Long = currentPositionMs()
    
    /**
     * Check if the current track is an SPC file (endless loop not supported).
     */
    fun isCurrentTrackSpc(): Boolean {
        val track = currentTrack ?: return false
        return track.displayName.endsWith(".spc", ignoreCase = true)
    }
    
    /**
     * Check if the current track supports playback speed control.
     * KSS, tracker formats (MOD, XM, S3M, IT), MIDI, and MUS don't support speed control.
     */
    fun isSpeedControlSupported(): Boolean {
        val track = currentTrack ?: return false
        val path = track.displayName.lowercase()
        // KSS files
        if (path.endsWith(".kss")) return false
        // Tracker formats
        if (path.endsWith(".mod") || path.endsWith(".xm") || 
            path.endsWith(".s3m") || path.endsWith(".it") ||
            path.endsWith(".mptm")) return false
        // MIDI files
        if (path.endsWith(".mid") || path.endsWith(".midi") ||
            path.endsWith(".rmi") || path.endsWith(".smf")) return false
        // MUS files (Doom music)
        if (path.endsWith(".mus") || path.endsWith(".lmp")) return false
        return true
    }
}
