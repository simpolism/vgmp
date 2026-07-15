package org.vlessert.vgmp.ui

import android.graphics.Color
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentNowPlayingBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.settings.SettingsManager
import org.vlessert.vgmp.playlists.PlaylistStore
import org.vlessert.vgmp.playlists.PlaylistTrack
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.ui.views.ChannelSpectrumView

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private val service: VgmPlaybackService? get() = (activity as? org.vlessert.vgmp.MainActivity)?.getService()
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    
    private var notesExpanded = false
    private var notesTrackKey: String? = null
    
    // Soloed channels tracking
    private val soloedChannels = mutableSetOf<Int>()
    private var channelControlsTrackKey: String? = null
    private var volumeControlsTrackKey: String? = null
    private var observationJob: Job? = null
    private var observedService: VgmPlaybackService? = null

    companion object {
        fun newInstance() = NowPlayingFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupSeekbar()
        updateUI()
        startPositionUpdater()
        observePlaybackInfo()

    }
    
    // Helper extension for building colored spanned strings
    private fun buildSpannedString(builderAction: SpannableStringBuilder.() -> Unit): Spanned {
        val builder = SpannableStringBuilder()
        builder.builderAction()
        return builder
    }
    
    private fun SpannableStringBuilder.colorSpan(text: String, color: Int): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    fun onServiceConnected(svc: VgmPlaybackService) {
        observePlaybackInfo()
    }

    private fun observePlaybackInfo() {
        val svc = service ?: return
        if (observedService === svc && observationJob?.isActive == true) return
        observedService = svc
        observationJob?.cancel()
        observationJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                svc.playbackInfo.collectLatest { info ->
                    updateUI()
                    if (info.endlessLoop) {
                        binding.tvTotalTime.text = "∞"
                    } else if (info.durationMs > 0) {
                        binding.tvTotalTime.text = formatTime(info.durationMs)
                        binding.seekBar.max = info.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                    updateEndlessLoopButton()
                }
            }
            launch {
                svc.channelSpectrums.collectLatest { spectrums ->
                    updateChannelSpectrums(spectrums)
                }
            }
        }
    }

    private suspend fun updateChannelSpectrums(spectrums: FloatArray?) {
        val container = binding.channelsMeterContainer
        if (spectrums == null || spectrums.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        
        val BANDS_PER_CH = 16
        val channelCount = spectrums.size / BANDS_PER_CH

        // Dynamically add views if count doesn't match
        if (container.childCount != channelCount) {
            container.removeAllViews()
            val names = List(channelCount) { VgmEngine.getChannelName(it) }
            for (i in 0 until channelCount) {
                val row = layoutInflater.inflate(R.layout.item_vu_meter, container, false)
                row.findViewById<TextView>(R.id.tv_channel_name).text = names[i]
                container.addView(row)
                row.findViewById<ChannelSpectrumView>(R.id.channel_spectrum)
                    .setSpectrum(spectrums, i * BANDS_PER_CH)
            }
        } else {
            // Update existing views
            for (i in 0 until channelCount) {
                val view = container.getChildAt(i) ?: continue
                val spectrumView = view.findViewById<ChannelSpectrumView>(R.id.channel_spectrum)
                spectrumView.setSpectrum(spectrums, i * BANDS_PER_CH)
            }
        }
    }

    private fun setupButtons() {
        binding.ivArt.setOnClickListener {
            (activity as? org.vlessert.vgmp.MainActivity)?.showAnalyzer()
        }
        binding.btnVisualizer.setOnClickListener {
            (activity as? org.vlessert.vgmp.MainActivity)?.showAnalyzer()
        }
        binding.tvNotes.setOnClickListener {
            notesExpanded = !notesExpanded
            applyNotesExpansion()
        }
        binding.btnPrev.setOnClickListener { service?.previousTrack() }
        binding.btnPlayPause.setOnClickListener {
            service?.let { svc ->
                if (svc.playing) svc.getMediaSession().controller.transportControls.pause()
                else svc.getMediaSession().controller.transportControls.play()
            }
            updatePlayPauseButton()
        }
        binding.btnNext.setOnClickListener { service?.nextTrack() }
        binding.btnRandom.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val nextMode = when (svc.getShuffle()) {
                VgmPlaybackService.ShuffleMode.OFF -> VgmPlaybackService.ShuffleMode.ON
                VgmPlaybackService.ShuffleMode.ON -> VgmPlaybackService.ShuffleMode.OFF
            }
            svc.setShuffle(nextMode)
            updateModeButtons()
            // Show tooltip
            val tooltipText = when (nextMode) {
                VgmPlaybackService.ShuffleMode.ON -> getString(R.string.random_queue)
                VgmPlaybackService.ShuffleMode.OFF -> getString(R.string.random_off)
            }
            showStyledToast(tooltipText)
        }
        binding.btnLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val nextMode = when (svc.getLoop()) {
                VgmPlaybackService.LoopMode.OFF -> VgmPlaybackService.LoopMode.TRACK
                VgmPlaybackService.LoopMode.TRACK -> VgmPlaybackService.LoopMode.QUEUE
                VgmPlaybackService.LoopMode.QUEUE -> VgmPlaybackService.LoopMode.OFF
            }
            svc.setLoop(nextMode)
            updateModeButtons()
            // Show tooltip
            val tooltipText = when (nextMode) {
                VgmPlaybackService.LoopMode.TRACK -> getString(R.string.loop_current_track)
                VgmPlaybackService.LoopMode.QUEUE -> getString(R.string.loop_current_queue)
                VgmPlaybackService.LoopMode.OFF -> getString(R.string.loop_off)
            }
            showStyledToast(tooltipText)
        }
        binding.btnTrackFavorite.setOnClickListener {
            addCurrentTrackToPlaylist()
        }
        binding.btnEndlessLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val newMode = !svc.getEndlessLoop()
            svc.setEndlessLoop(newMode)
            updateEndlessLoopButton()
            showStyledToast(if (newMode) "Endless loop enabled" else "Endless loop disabled")
        }
        binding.btnTiming.setOnClickListener {
            val hz = service?.cycleVgmPlaybackHz() ?: return@setOnClickListener
            updateTimingButton()
            showStyledToast(
                when (hz) {
                    60 -> "VGM timing: 60 Hz (NTSC)"
                    50 -> "VGM timing: 50 Hz (PAL)"
                    else -> "VGM timing: Auto (use file header)"
                }
            )
        }
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { 
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                isSeeking = true 
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                isSeeking = false
                val seekMs = (seekBar?.progress ?: 0).toLong()
                service?.getMediaSession()?.controller?.transportControls?.seekTo(seekMs)
            }
        })
    }

    private fun updateUI() {
        val binding = _binding ?: return
        val svc = service ?: return
        val track = svc.currentTrack
        if (track == null) {
            binding.tvTitle.text = getString(R.string.no_track_playing)
            binding.tvGame.text = ""
            binding.tvSystem.text = ""
            binding.tvAuthor.text = ""
            binding.tvCreator.text = ""
            binding.tvDate.text = ""
            binding.tvNotes.text = ""
            binding.ivArt.setImageResource(R.drawable.vgmp_logo)
            return
        }
        val trackKey = "${track.uri}#${track.archiveEntry}#${track.subtrackIndex}"
        if (trackKey != notesTrackKey) {
            notesTrackKey = trackKey
            notesExpanded = false
            applyNotesExpansion()
        }

        svc.artwork.value?.let(binding.ivArt::setImageBitmap)
            ?: binding.ivArt.setImageResource(R.drawable.vgmp_logo)

        // Duration is now updated via observePlaybackInfo() using live duration from engine
        
        viewLifecycleOwner.lifecycleScope.launch {
            // Use the service's currentTags which has fallback to database values
            val tags = svc.getCurrentTags()
            binding.tvTitle.text = tags.displayTitle
            binding.tvGame.text = tags.displayGame
            
            // System with white value
            if (tags.displaySystem.isNotEmpty()) {
                binding.tvSystem.text = buildSpannedString {
                    colorSpan("System: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.displaySystem, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvSystem.visibility = View.VISIBLE
            } else {
                binding.tvSystem.visibility = View.GONE
            }
            
            // Composers with white value
            if (tags.displayAuthor.isNotEmpty()) {
                binding.tvAuthor.text = buildSpannedString {
                    colorSpan("Composers: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.displayAuthor, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvAuthor.visibility = View.VISIBLE
            } else {
                binding.tvAuthor.visibility = View.GONE
            }
            
            // Pack creator on separate line
            if (tags.creator.isNotEmpty()) {
                binding.tvCreator.text = buildSpannedString {
                    colorSpan("Creator: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.creator, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvCreator.visibility = View.VISIBLE
            } else {
                binding.tvCreator.visibility = View.GONE
            }
            
            // Release year on separate line
            if (tags.date.isNotEmpty()) {
                binding.tvDate.text = buildSpannedString {
                    colorSpan("Year: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.date, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvDate.visibility = View.VISIBLE
            } else {
                binding.tvDate.visibility = View.GONE
            }
            
            // Notes with label
            if (tags.notes.isNotEmpty()) {
                binding.tvNotes.text = buildSpannedString {
                    colorSpan("Notes: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.notes, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvNotes.visibility = View.VISIBLE
                binding.tvNotes.contentDescription = if (notesExpanded) "Collapse notes" else "Expand notes"
            } else {
                binding.tvNotes.visibility = View.GONE
            }
            
            updateVolumeSliders()
            updateChannelControls()
        }
        
        updatePlayPauseButton()
        updateModeButtons()
    }

    private fun applyNotesExpansion() {
        val binding = _binding ?: return
        binding.tvNotes.maxLines = if (notesExpanded) Int.MAX_VALUE else 3
        binding.tvNotes.ellipsize = if (notesExpanded) null else TextUtils.TruncateAt.END
        binding.tvNotes.contentDescription = if (notesExpanded) "Collapse notes" else "Expand notes"
    }

    private fun updateModeButtons() {
        val binding = _binding ?: return
        val svc = service ?: return
        
        // Random button color/icon
        when (svc.getShuffle()) {
            VgmPlaybackService.ShuffleMode.OFF -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
                binding.btnRandom.alpha = 0.5f
            }
            VgmPlaybackService.ShuffleMode.ON -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
                binding.btnRandom.alpha = 1.0f
            }
        }
        
        // Loop button color - different colors for TRACK vs GAME mode
        when (svc.getLoop()) {
            VgmPlaybackService.LoopMode.OFF -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
                binding.btnLoop.alpha = 0.5f
            }
            VgmPlaybackService.LoopMode.TRACK -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
                binding.btnLoop.alpha = 1.0f
            }
            VgmPlaybackService.LoopMode.QUEUE -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.white, null))
                binding.btnLoop.alpha = 1.0f
            }
        }
        
        // Update add-to-playlist button
        updateTrackFavoriteButton()
        
        // Update endless loop button
        updateEndlessLoopButton()
        
        updateTimingButton()
    }

    private fun updateTrackFavoriteButton() {
        val binding = _binding ?: return
        val track = service?.currentTrack
        if (track == null) {
            binding.btnTrackFavorite.visibility = View.GONE
            return
        }
        binding.btnTrackFavorite.visibility = View.VISIBLE
        binding.btnTrackFavorite.setImageResource(R.drawable.ic_playlist)
        binding.btnTrackFavorite.contentDescription = "Add to playlist"
    }

    private fun addCurrentTrackToPlaylist() {
        val document = service?.currentTrack ?: return
        val playlists = PlaylistStore.getAll(requireContext())
        val labels = (playlists.map { it.name } + "＋ New playlist").toTypedArray()
        AlertDialog.Builder(requireContext()).setTitle("Add to playlist").setItems(labels) { _, index ->
            if (index < playlists.size) {
                PlaylistStore.addTrack(
                    requireContext(), playlists[index].id,
                    PlaylistTrack.from(document)
                )
                showStyledToast("Added to ${playlists[index].name}")
            } else {
                val input = EditText(requireContext()).apply { hint = "Playlist name" }
                AlertDialog.Builder(requireContext()).setTitle("New playlist").setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            val playlist = PlaylistStore.create(requireContext(), name)
                            PlaylistStore.addTrack(
                                requireContext(), playlist.id,
                                PlaylistTrack.from(document)
                            )
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun updateEndlessLoopButton() {
        val binding = _binding ?: return
        val svc = service ?: return
        val endlessLoop = svc.getEndlessLoop()
        
        binding.btnEndlessLoop.visibility = View.VISIBLE
        
        if (endlessLoop) {
            binding.btnEndlessLoop.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
            binding.btnEndlessLoop.alpha = 1.0f
            // Show infinity symbol instead of duration
            binding.tvTotalTime.text = "∞"
            // Disable seekbar
            binding.seekBar.isEnabled = false
            binding.seekBar.alpha = 0.5f
        } else {
            binding.btnEndlessLoop.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
            binding.btnEndlessLoop.alpha = 0.5f
            // Re-enable seekbar
            binding.seekBar.isEnabled = true
            binding.seekBar.alpha = 1.0f
        }
    }

    private fun updateTimingButton() {
        val binding = _binding ?: return
        val svc = service ?: return
        val supported = svc.isVgmTimingSupported()
        val hz = svc.getVgmPlaybackHz()
        binding.btnTiming.isEnabled = supported
        binding.btnTiming.text = if (hz == 0) "AUTO" else hz.toString()
        binding.btnTiming.contentDescription = when (hz) {
            60 -> "VGM timing: 60 hertz"
            50 -> "VGM timing: 50 hertz PAL"
            else -> "VGM timing: Auto"
        }
        binding.btnTiming.setTextColor(
            resources.getColor(if (hz == 0) R.color.vgmp_text_secondary else R.color.vgmp_accent, null)
        )
        binding.btnTiming.alpha = when {
            !supported -> 0.25f
            hz == 0 -> 0.55f
            else -> 1f
        }
    }

    private fun showStyledToast(message: String) {
        val context = context ?: return
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.custom_toast, null)
        val textView = layout.findViewById<TextView>(R.id.toast_text)
        textView.text = message
        
        val toast = Toast(context)
        toast.duration = Toast.LENGTH_SHORT
        toast.view = layout
        toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
        toast.show()
    }

    private suspend fun updateVolumeSliders() {
        val binding = _binding ?: return
        val container = binding.volumesContainer
        val header = binding.tvSoundChipsHeader
        
        val count = VgmEngine.getDeviceCount()
        val track = service?.currentTrack
        val trackKey = track?.let { "${it.uri}#${it.archiveEntry}#${it.subtrackIndex}" }
        val trackChanged = trackKey != volumeControlsTrackKey
        volumeControlsTrackKey = trackKey
        
        // Show/hide header based on whether there are chips
        header.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
        
        // Only clear and rebuild if count changed or container is empty
        if (trackChanged || container.childCount != count) {
            container.removeAllViews()
            for (i in 0 until count) {
                val name = VgmEngine.getDeviceName(i)
                val vol = VgmEngine.getDeviceVolume(i)
                
                val view = LayoutInflater.from(context).inflate(R.layout.item_chip_volume, container, false)
                view.findViewById<android.widget.TextView>(R.id.tv_chip_name).text = name
                val sb = view.findViewById<SeekBar>(R.id.sb_chip_volume)
                sb.progress = vol
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                VgmEngine.setDeviceVolume(i, p)
                                SettingsManager.setChipVolume(requireContext(), name, p)
                            }
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                container.addView(view)
            }
        }
    }

    private suspend fun updateChannelControls() {
        val track = service?.currentTrack
        val trackKey = track?.let { "${it.uri}#${it.archiveEntry}#${it.subtrackIndex}" }
        val trackChanged = trackKey != channelControlsTrackKey
        if (trackChanged) {
            channelControlsTrackKey = trackKey
            soloedChannels.clear()
        }
        val binding = _binding ?: return
        val container = binding.channelsContainer
        val header = binding.tvChannelsHeader
        
        val count = VgmEngine.getChannelCount()
        
        // Show/hide header based on whether there are channels
        header.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
        
        // Only clear and rebuild if count changed or container is empty
        if (trackChanged || container.childCount != count) {
            container.removeAllViews()
            for (i in 0 until count) {
                val name = VgmEngine.getChannelName(i)
                val deviceName = VgmEngine.getChannelDeviceName(i)
                
                val view = LayoutInflater.from(context).inflate(R.layout.item_channel, container, false)
                view.findViewById<android.widget.TextView>(R.id.tv_channel_name).text = name
                
                // Mute button
                val btnMute = view.findViewById<android.widget.ImageButton>(R.id.btn_channel_mute)
                val isMuted = VgmEngine.isChannelMuted(i)
                btnMute.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                btnMute.setColorFilter(resources.getColor(if (isMuted) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
                btnMute.setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val currentMuted = VgmEngine.isChannelMuted(i)
                        val newMuted = !currentMuted
                        VgmEngine.setChannelMuted(i, newMuted)
                        
                        // Update button state
                        btnMute.setImageResource(if (newMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                        btnMute.setColorFilter(resources.getColor(if (newMuted) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
                        
                        // If we're unmuting and there are soloed channels, keep it muted
                        if (newMuted == false && soloedChannels.isNotEmpty() && !soloedChannels.contains(i)) {
                            VgmEngine.setChannelMuted(i, true)
                            btnMute.setImageResource(R.drawable.ic_volume_off)
                            btnMute.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
                        }
                    }
                }
                
                // Solo button
                val btnSolo = view.findViewById<android.widget.ImageButton>(R.id.btn_channel_solo)
                btnSolo.setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!soloedChannels.add(i)) soloedChannels.remove(i)
                        applySoloMix(container)
                    }
                }
                
                container.addView(view)
            }
        } else {
            // If count is same, just update existing views with current state
            for (i in 0 until count) {
                val child = container.getChildAt(i)
                val btnMute = child.findViewById<android.widget.ImageButton>(R.id.btn_channel_mute)
                val btnSolo = child.findViewById<android.widget.ImageButton>(R.id.btn_channel_solo)
                
                // Update mute button
                val isMuted = VgmEngine.isChannelMuted(i)
                btnMute.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                btnMute.setColorFilter(resources.getColor(if (isMuted) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
                
                // Update solo button
                val isSoloed = soloedChannels.contains(i)
                btnSolo.setImageResource(if (isSoloed) R.drawable.ic_volume_solo else R.drawable.ic_volume_up)
                btnSolo.setColorFilter(resources.getColor(if (isSoloed) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
            }
        }
    }

    private fun isChannelSoloed(channelIndex: Int): Boolean {
        return soloedChannels.contains(channelIndex)
    }

    private suspend fun applySoloMix(container: ViewGroup) {
        val count = VgmEngine.getChannelCount()
        for (i in 0 until count) {
            val selected = i in soloedChannels
            val muted = soloedChannels.isNotEmpty() && !selected
            VgmEngine.setChannelMuted(i, muted)
            val child = container.getChildAt(i) ?: continue
            child.findViewById<android.widget.ImageButton>(R.id.btn_channel_solo)?.apply {
                setImageResource(if (selected) R.drawable.ic_volume_solo else R.drawable.ic_volume_up)
                setColorFilter(resources.getColor(if (selected) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
            }
            child.findViewById<android.widget.ImageButton>(R.id.btn_channel_mute)?.apply {
                setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                setColorFilter(resources.getColor(if (muted) R.color.vgmp_accent else R.color.vgmp_text_secondary, null))
            }
        }
    }

    private fun updatePlayPauseButton() {
        val binding = _binding ?: return
        val playing = service?.playing ?: false
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnVisualizer.isEnabled = service?.currentTrack != null
    }

    private fun startPositionUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                val binding = _binding ?: return
                if (!isAdded || isRemoving) return
                val svc = service ?: run { handler.postDelayed(this, 500); return }
                // Use direct position from service instead of MediaSession's static position
                val posMs = svc.getCurrentPositionMs()
                val durMs = svc.playbackInfo.value.durationMs
                val endlessLoop = svc.getEndlessLoop()
                
                // In endless loop mode, just update current time, not the seekbar
                if (endlessLoop) {
                    binding.tvCurrentTime.text = formatTime(posMs)
                } else if (durMs > 0 && !isSeeking) {
                    binding.seekBar.max = durMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    binding.seekBar.progress = posMs.coerceIn(0L, binding.seekBar.max.toLong()).toInt()
                    binding.tvCurrentTime.text = formatTime(posMs)
                }
                updatePlayPauseButton()
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val secs = ms / 1000
        return "%d:%02d".format(secs / 60, secs % 60)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        observationJob?.cancel()
        observationJob = null
        observedService = null
        super.onDestroyView()
        _binding = null
    }
}
