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
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.VgmServiceBinder
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.engine.VgmTags
import org.vlessert.vgmp.playback.ArtworkLoader
import org.vlessert.vgmp.playback.ArtworkResolver
import org.vlessert.vgmp.playback.PlaybackQueue
import org.vlessert.vgmp.playback.QueueStateStore
import org.vlessert.vgmp.playback.SupportedFormats
import org.vlessert.vgmp.playback.TrackRef
import org.vlessert.vgmp.playback.ZipArchiveStore
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.settings.SettingsManager
import org.vlessert.vgmp.settings.nextVgmPlaybackHz
import org.vlessert.vgmp.settings.normalizeLoopRepeats
import org.vlessert.vgmp.settings.normalizeVgmPlaybackHz
import java.io.File
import java.io.IOException
import android.widget.Toast

class VgmPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val NOTIF_CHANNEL_ID = "vgmp_playback"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 44100
        // 128-frame hops provide fresh FFT input for analyzer rates up to 240 Hz.
        // AudioTrack uses a deeper device buffer below to isolate playback from UI/FFT jitter.
        const val BUFFER_FRAMES = 128
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
    private val _queueTracks = MutableStateFlow<List<TrackRef>>(emptyList())
    val queueTracks: StateFlow<List<TrackRef>> = _queueTracks.asStateFlow()
    private val engineReady = CompletableDeferred<Unit>()
    private val restoreComplete = CompletableDeferred<Unit>()
    private var queueReplaceSnapshot: Pair<List<TrackRef>, Int>? = null
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
    private val _artwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()
    private var metadataArtwork: Bitmap? = null
    
    // Channel spectrums thread
    private val _channelSpectrums = MutableStateFlow<FloatArray?>(null)
    val channelSpectrums: StateFlow<FloatArray?> = _channelSpectrums.asStateFlow()
    
    private var lastSpectrumUpdateNs = 0L
    private var lastChannelSpectrumUpdateNs = 0L
    @Volatile private var visualizerActive = false
    @Volatile private var visualizerFps = 42
    private var lastUnderrunCount = 0

    private var renderJob: Job? = null
    private var engineCleanupJob: Job? = null
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

    // Engine-derived position avoids wall-clock drift during stalls and underruns.
    @Volatile private var enginePositionMs = 0L
    private var pausedPositionMs = 0L

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        setupMediaSession()
        
        VgmEngine.nSetSampleRate(SAMPLE_RATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Initialize the engine and the two ROM resources used by supported formats.
        serviceScope.launch {
            VgmEngine.setSampleRate(SAMPLE_RATE) // Use thread-safe version
            extractRoms()
            VgmEngine.setBassEnabled(SettingsManager.isBassEnabled(applicationContext))
            VgmEngine.setReverbEnabled(SettingsManager.isReverbEnabled(applicationContext))
            engineReady.complete(Unit)
            try {
                restorePersistedQueue()
            } finally {
                restoreComplete.complete(Unit)
            }
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
                playlist.tracks.map { it.toTrackRef() },
                index
            )
        }
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val needle = query?.trim().orEmpty()
            if (needle.isEmpty()) return
            val playlists = PlaylistStore.getAll(applicationContext)
            playlists.firstOrNull { it.name.contains(needle, ignoreCase = true) }?.let {
                playQueue(it.tracks.map { track -> track.toTrackRef() }, 0)
                return
            }
            playlists.forEach { playlist ->
                val index = playlist.tracks.indexOfFirst {
                    it.displayName.contains(needle, ignoreCase = true)
                }
                if (index >= 0) {
                    playQueue(playlist.tracks.map { it.toTrackRef() }, index)
                    return
                }
            }
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            loopMode = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> LoopMode.TRACK
                PlaybackStateCompat.REPEAT_MODE_ALL -> LoopMode.QUEUE
                else -> LoopMode.OFF
            }
            saveQueueState()
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            this@VgmPlaybackService.shuffleMode = when(shuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL,
                PlaybackStateCompat.SHUFFLE_MODE_GROUP -> ShuffleMode.ON
                else -> ShuffleMode.OFF
            }
            saveQueueState()
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
        // Favor glitch resistance over minimum latency for a music player. This gives the render
        // thread headroom when an emulator or FFT frame occasionally takes longer than usual.
        val bufSize = maxOf(minBuf * 4, BUFFER_FRAMES * 2 * 2)
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
        val requested = tracks.getOrNull(startIndex)
        val playable = tracks.filter { isSupportedDocument(it.displayName) }
        val playableIndex = requested?.let(playable::indexOf)?.takeIf { it >= 0 } ?: 0
        if (playable.isEmpty()) {
            Toast.makeText(applicationContext, "No playable tracks", Toast.LENGTH_SHORT).show()
            return
        }
        if (queue.tracks.isNotEmpty()) queueReplaceSnapshot = queue.tracks to queue.index
        replaceQueue(playable, playableIndex)
        serviceScope.launch {
            engineReady.await()
            startCurrentTrack()
        }
    }

    fun playDocument(uri: Uri, displayName: String) = playQueue(listOf(TrackRef(uri, displayName)))

    private fun replaceQueue(tracks: List<TrackRef>, index: Int) {
        queue.replace(tracks, index)
        _queueTracks.value = queue.tracks
        saveQueueState()
    }

    fun undoQueueReplacement(): Boolean {
        val snapshot = queueReplaceSnapshot ?: return false
        queueReplaceSnapshot = null
        replaceQueue(snapshot.first, snapshot.second)
        serviceScope.launch { startCurrentTrack() }
        return true
    }

    fun addToQueue(track: TrackRef, playNext: Boolean = false) {
        if (!isSupportedDocument(track.displayName)) return
        if (playNext) queue.insertNext(track) else queue.add(track)
        _queueTracks.value = queue.tracks
        saveQueueState()
    }

    fun removeQueueAt(position: Int) {
        val result = queue.removeAt(position) ?: return
        _queueTracks.value = queue.tracks
        saveQueueState()
        if (result.removedCurrent) {
            if (result.newCurrent == null) stopPlayback()
            else serviceScope.launch { startCurrentTrack() }
        } else {
            _playbackState.value = _playbackState.value.copy(queueIndex = queue.index)
        }
    }

    fun moveQueueItem(from: Int, to: Int): Boolean {
        if (!queue.move(from, to)) return false
        _queueTracks.value = queue.tracks
        _playbackState.value = _playbackState.value.copy(queueIndex = queue.index)
        saveQueueState()
        return true
    }

    fun playQueueIndex(index: Int) {
        if (index !in queue.tracks.indices) return
        queue.replace(queue.tracks, index)
        _queueTracks.value = queue.tracks
        saveQueueState()
        serviceScope.launch { startCurrentTrack() }
    }

    private suspend fun restorePersistedQueue() {
        val saved = withContext(Dispatchers.IO) { QueueStateStore.load(applicationContext) } ?: return
        val playable = withContext(Dispatchers.IO) {
            saved.tracks.filter { track ->
                isSupportedDocument(track.displayName) && runCatching {
                    contentResolver.openAssetFileDescriptor(track.uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
        if (playable.isEmpty()) {
            QueueStateStore.clear(applicationContext)
            return
        }
        val savedCurrent = saved.tracks.getOrNull(saved.index)
        val restoredIndex = savedCurrent?.let(playable::indexOf)?.takeIf { it >= 0 } ?: 0
        shuffleMode = runCatching { ShuffleMode.valueOf(saved.shuffleMode) }.getOrDefault(ShuffleMode.OFF)
        loopMode = runCatching { LoopMode.valueOf(saved.loopMode) }.getOrDefault(LoopMode.OFF)
        replaceQueue(playable, restoredIndex)
        var position = saved.positionMs
        while (queue.current != null && !startCurrentTrack(position, restorePaused = true)) {
            queue.removeAt(queue.index)
            _queueTracks.value = queue.tracks
            position = 0L
        }
        if (queue.current == null) QueueStateStore.clear(applicationContext) else saveQueueState()
    }

    private fun saveQueueState() {
        QueueStateStore.saveQueue(
            applicationContext,
            queue.tracks,
            queue.index,
            shuffleMode.name,
            loopMode.name
        )
        QueueStateStore.savePosition(applicationContext, currentPositionMs())
    }

    private suspend fun startCurrentTrack(
        restoredPositionMs: Long = 0L,
        restorePaused: Boolean = false
    ): Boolean = coroutineScope {
        engineReady.await()
        val track = queue.current ?: return@coroutineScope false
        _artwork.value = null
        metadataArtwork = null
        val artworkLoad = async(Dispatchers.IO) {
            ArtworkResolver.resolve(applicationContext, track)
                ?.let { ArtworkLoader.load(applicationContext, it) }
        }
        val path = materialize(track) ?: run {
            artworkLoad.cancel()
            return@coroutineScope false
        }
        _artwork.value = artworkLoad.await()
        metadataArtwork = _artwork.value?.let(::scaledMetadataArtwork)
        currentLocalPath = path
        startTrack(track, path, restoredPositionMs, restorePaused)
        true
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

    private suspend fun startTrack(
        track: TrackRef,
        path: String,
        restoredPositionMs: Long = 0L,
        restorePaused: Boolean = false
    ) {
        stopRenderJob()

        if (restorePaused) {
            startTrackWithFocus(track, path, restoredPositionMs, true)
            return
        }
        
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
        
        startTrackWithFocus(track, path, restoredPositionMs, false)
    }

    private suspend fun startTrackWithFocus(
        track: TrackRef,
        path: String,
        restoredPositionMs: Long = 0L,
        restorePaused: Boolean = false
    ) {
        VgmEngine.setVgmPlaybackHz(SettingsManager.getVgmPlaybackHz(applicationContext))
        VgmEngine.setLoopRepeatCount(SettingsManager.getLoopRepeats(applicationContext))
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

        val initialPosition = restoredPositionMs.coerceIn(
            0L,
            trackDurationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        )
        if (initialPosition > 0) {
            VgmEngine.seek(initialPosition * SAMPLE_RATE / 1000L)
        }

        // Update MediaSession metadata (→ AVRCP 1.6)
        updateMediaSessionMetadata()

        // Start audio track and render loop
        isPlaying = true
        isPaused = restorePaused
        enginePositionMs = initialPosition
        pausedPositionMs = initialPosition

        audioTrack?.release()
        audioTrack = createAudioTrack().also { if (!restorePaused) it.play() }
        lastUnderrunCount = 0

        startRenderJob()
        if (restorePaused) {
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification(false)
        } else {
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIF_ID, buildNotification(true))
        }
        _playbackState.value = PlaybackInfo(true, restorePaused, queue.index, track, trackDurationMs)
        saveQueueState()
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

                        // Fullscreen FFT work is demand-driven; normal playback should not pay for it.
                        val nowSpectrum = SystemClock.elapsedRealtimeNanos()
                        val spectrumIntervalNs = 1_000_000_000L / visualizerFps
                        if (visualizerActive && nowSpectrum - lastSpectrumUpdateNs >= spectrumIntervalNs) {
                            // Advance the deadline rather than resetting it to `now`. Render chunks
                            // do not divide evenly into every display rate; preserving the remainder
                            // avoids turning a requested 120 FPS into ~86 FPS.
                            lastSpectrumUpdateNs = if (
                                lastSpectrumUpdateNs == 0L ||
                                nowSpectrum - lastSpectrumUpdateNs > spectrumIntervalNs * 2
                            ) nowSpectrum else lastSpectrumUpdateNs + spectrumIntervalNs
                            VgmEngine.getSpectrum(spectrumBuffer)
                            _spectrum.emit(spectrumBuffer.copyOf())
                        }

                        // Channel meters have their own modest cadence and do not scale with the
                        // fullscreen visualizer FPS setting.
                        val channelIntervalNs = 1_000_000_000L / 30L
                        if (nowSpectrum - lastChannelSpectrumUpdateNs >= channelIntervalNs) {
                            lastChannelSpectrumUpdateNs = nowSpectrum
                            val trackPath = currentLocalPath.orEmpty()
                            val channelData = if (
                                trackPath.endsWith(".kss", ignoreCase = true) ||
                                trackPath.endsWith(".mgs", ignoreCase = true)
                            ) VgmEngine.getChannelSpectrums() else null
                            _channelSpectrums.emit(channelData)
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
                        enginePositionMs = VgmEngine.getCurrentSample() * 1000L / SAMPLE_RATE
                        QueueStateStore.savePosition(applicationContext, enginePositionMs)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val underruns = audioTrack?.underrunCount ?: 0
                            if (underruns > lastUnderrunCount) {
                                Log.w(TAG, "AudioTrack underruns: $lastUnderrunCount -> $underruns")
                                lastUnderrunCount = underruns
                            }
                        }
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
        val fadeFactor = (1.0f - (elapsed.toFloat() / FADE_MS)).coerceIn(0f, 1f)
        
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
        if (!restoreComplete.isCompleted) {
            serviceScope.launch {
                restoreComplete.await()
                resumeOrPlay()
            }
            return
        }
        if (!isPlaying) {
            if (queue.current != null) serviceScope.launch { startCurrentTrack() }
            else stopSelf()
            return
        }
        if (isPaused) {
            if (!hasAudioFocus) {
                if (!requestAudioFocus()) return
                if (!hasAudioFocus) {
                    shouldPlayAfterFocusGain = true
                    return
                }
            }
            isPaused = false
            audioTrack?.play()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIF_ID, buildNotification(true))
            _playbackState.value = _playbackState.value.copy(paused = false, playing = true)
        }
    }

    private fun pausePlayback() {
        if (!isPlaying || isPaused) return
        isPaused = true
        pausedPositionMs = enginePositionMs
        audioTrack?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        _playbackState.value = _playbackState.value.copy(paused = true, playing = true)
        saveQueueState()
    }

    private fun stopPlayback(persistReset: Boolean = true) {
        isPlaying = false
        isPaused = false
        enginePositionMs = 0L
        pausedPositionMs = 0L
        stopRenderJob()
        engineCleanupJob?.cancel()
        engineCleanupJob = serviceScope.launch(Dispatchers.IO) {
            VgmEngine.stop()
            VgmEngine.close()
        }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        abandonAudioFocus()
        _artwork.value = null
        metadataArtwork = null
        _playbackState.value = PlaybackInfo()
        if (persistReset) saveQueueState()
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
            if (currentPositionMs() > 3_000L) {
                seekTo(0L)
                return@launch
            }
            if (!isFadingOut) {
                startFadeOut()
                delay(500)
            }
            val previous = if (shuffleMode == ShuffleMode.ON) {
                queue.movePreviousRandom()
            } else {
                queue.movePrevious()
            }
            if (previous != null) startCurrentTrack()
        }
    }
    private fun seekTo(posMs: Long) {
        val maximum = trackDurationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val clamped = posMs.coerceIn(0L, maximum)
        isFadingOut = false
        fadeStartTimeMs = 0L
        val samplePos = clamped * SAMPLE_RATE / 1000L
        serviceScope.launch(Dispatchers.IO) { VgmEngine.seek(samplePos) }
        enginePositionMs = clamped
        pausedPositionMs = clamped
        updatePlaybackState(if (isPaused) PlaybackStateCompat.STATE_PAUSED
                            else PlaybackStateCompat.STATE_PLAYING)
    }

    // ------- Playback state / notification -------

    private fun currentPositionMs(): Long {
        return if (!isPlaying) 0L else if (isPaused) pausedPositionMs else enginePositionMs
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
        result.detach()
        serviceScope.launch(Dispatchers.IO) {
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()
            val playlists = PlaylistStore.getAll(applicationContext)
            if (parentId == MEDIA_ID_ROOT) {
                playlists.forEach { playlist ->
                    val icon = playlist.tracks.firstOrNull()?.toTrackRef()?.let(::getScaledArtForAuto)
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId("playlist/${playlist.id}")
                        .setTitle(playlist.name)
                        .setSubtitle("${playlist.tracks.size} tracks")
                        .setIconBitmap(icon ?: getScaledArtForAuto())
                        .build()
                    items += MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
                }
            } else if (parentId.startsWith("playlist/")) {
                val playlistId = parentId.removePrefix("playlist/")
                playlists.firstOrNull { it.id == playlistId }?.tracks?.forEachIndexed { index, track ->
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId("track/$playlistId/$index")
                        .setTitle(track.displayName.substringBeforeLast('.', track.displayName))
                        .setIconBitmap(getScaledArtForAuto(track.toTrackRef()) ?: getScaledArtForAuto())
                        .build()
                    items += MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                }
            }
            result.sendResult(items)
        }
    }
    // Get scaled art for Android Auto (256x256 is recommended size)
    private fun getScaledArtForAuto(track: TrackRef? = null): Bitmap? {
        val sourceBitmap = track?.let { ref ->
            ArtworkResolver.resolve(applicationContext, ref)
                ?.let { ArtworkLoader.load(applicationContext, it, 256) }
        } ?: getFallbackArt() ?: return null

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
        MediaButtonReceiver.handleIntent(mediaSession, intent)
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
        saveQueueState()
        stopPlayback(persistReset = false)
        runBlocking {
            withTimeoutOrNull(5_000L) { engineCleanupJob?.join() }
        }
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Expose state to bound activities ---
    val currentTrack: TrackRef? get() = queue.current
    val playing: Boolean get() = isPlaying && !isPaused
    val paused: Boolean get() = isPlaying && isPaused
    fun getMediaSession() = mediaSession
    fun setVisualizerActive(active: Boolean) {
        visualizerActive = active
        if (active) {
            visualizerFps = SettingsManager.getVisualizerFps(applicationContext)
            lastSpectrumUpdateNs = 0L
        }
    }

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
    
    fun setShuffle(mode: ShuffleMode) { shuffleMode = mode; saveQueueState() }
    fun getShuffle(): ShuffleMode = shuffleMode
    fun setLoop(mode: LoopMode) { loopMode = mode; saveQueueState() }
    fun getLoop(): LoopMode = loopMode
    
    // Legacy boolean setter for compatibility
    fun setLoopEnabled(enabled: Boolean) { 
        loopMode = if (enabled) LoopMode.TRACK else LoopMode.OFF 
        saveQueueState()
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
        (metadataArtwork ?: getFallbackArt())?.let {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        mediaSession.setMetadata(metaBuilder.build())
    }

    private fun scaledMetadataArtwork(source: Bitmap): Bitmap {
        val maxDimension = 384
        val largest = maxOf(source.width, source.height)
        if (largest <= maxDimension) return source
        val scale = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
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
    
    fun isVgmTimingSupported(): Boolean = currentTrack?.displayName?.lowercase()?.let {
        it.endsWith(".vgm") || it.endsWith(".vgz")
    } == true

    fun getVgmPlaybackHz(): Int = SettingsManager.getVgmPlaybackHz(applicationContext)

    fun setVgmPlaybackHz(hz: Int) {
        val normalized = normalizeVgmPlaybackHz(hz)
        SettingsManager.setVgmPlaybackHz(applicationContext, normalized)
        serviceScope.launch {
            VgmEngine.setVgmPlaybackHz(normalized)
            if (isPlaying && isVgmTimingSupported()) {
                refreshTimelineFromEngine()
            }
        }
    }

    /** Cycle the persistent libvgm timing override: header/auto → 60 Hz → 50 Hz. */
    fun cycleVgmPlaybackHz(): Int {
        val next = nextVgmPlaybackHz(getVgmPlaybackHz())
        setVgmPlaybackHz(next)
        return next
    }

    fun getLoopRepeats(): Int = SettingsManager.getLoopRepeats(applicationContext)

    fun setLoopRepeats(repeats: Int) {
        val normalized = normalizeLoopRepeats(repeats)
        SettingsManager.setLoopRepeats(applicationContext, normalized)
        serviceScope.launch {
            VgmEngine.setLoopRepeatCount(normalized)
            if (isPlaying) refreshTimelineFromEngine()
        }
    }

    private suspend fun refreshTimelineFromEngine() {
        val positionMs = VgmEngine.getCurrentSample() * 1000L / SAMPLE_RATE
        val durationSamples = VgmEngine.getTotalSamples()
        trackDurationMs = if (durationSamples > 0) durationSamples * 1000L / SAMPLE_RATE else 0L
        enginePositionMs = positionMs
        if (isPaused) pausedPositionMs = positionMs
        updateMediaSessionMetadata()
        updatePlaybackState(
            if (isPaused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        )
        _playbackState.value = _playbackState.value.copy(durationMs = trackDurationMs)
        updateNotification(isPlaying && !isPaused)
    }
}
