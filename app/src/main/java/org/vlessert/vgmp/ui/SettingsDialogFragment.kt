package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentSettingsBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.settings.SettingsManager
import org.vlessert.vgmp.playback.ZipArchiveStore

class SettingsDialogFragment : InsetAwareDialogFragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        binding.toolbar.setNavigationOnClickListener { dismissAllowingStateLoss() }
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = requireContext()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        binding.tvVersion.text = "VGMP $version"
        binding.switchBassEnabled.isChecked = SettingsManager.isBassEnabled(context)
        binding.switchReverbEnabled.isChecked = SettingsManager.isReverbEnabled(context)
        binding.switchOpenPlayerOnSelection.isChecked = SettingsManager.openPlayerOnSelection(context)
        binding.switchZipBrowsing.isChecked = SettingsManager.isZipBrowsingEnabled(context)
        updateCacheSize()
        val loopRepeats = SettingsManager.getLoopRepeats(context)
        binding.seekbarLoopRepeats.progress = loopRepeats
        binding.tvLoopRepeats.text = loopRepeatLabel(loopRepeats)
        when (SettingsManager.getAnalyzerStyle(context)) {
            SettingsManager.ANALYZER_STYLE_BARS -> binding.radioBars.isChecked = true
            else -> binding.radioKaleidoscope.isChecked = true
        }
        when (SettingsManager.getVisualizerAxis(context)) {
            SettingsManager.VISUALIZER_AXIS_LOG -> binding.radioAxisLog.isChecked = true
            else -> binding.radioAxisLinear.isChecked = true
        }
        when (SettingsManager.getVisualizerResponse(context)) {
            SettingsManager.VISUALIZER_RESPONSE_BALANCED -> binding.radioResponseBalanced.isChecked = true
            else -> binding.radioResponseRaw.isChecked = true
        }
        when (SettingsManager.getVgmPlaybackHz(context)) {
            60 -> binding.radioTiming60.isChecked = true
            50 -> binding.radioTiming50.isChecked = true
            else -> binding.radioTimingAuto.isChecked = true
        }
        val maxFps = VisualizerRefreshRate.maxSupportedFps(requireActivity())
        val configuredFps = SettingsManager.getVisualizerFps(context)
        val fps = clampVisualizerFps(configuredFps, maxFps)
        if (fps != configuredFps) SettingsManager.setVisualizerFps(context, fps)
        binding.tvVisualizerRefreshLabel.text = "Target frame rate (display max $maxFps Hz)"
        binding.seekbarVisualizerFps.max = maxFps - VisualizerRefreshRate.MIN_FPS
        binding.seekbarVisualizerFps.progress = fps - VisualizerRefreshRate.MIN_FPS
        binding.tvVisualizerFps.text = "$fps FPS"
    }

    private fun setupListeners() {
        val context = requireContext()
        binding.switchBassEnabled.setOnCheckedChangeListener { _, enabled ->
            SettingsManager.setBassEnabled(context, enabled)
            lifecycleScope.launch { VgmEngine.setBassEnabled(enabled) }
        }
        binding.switchReverbEnabled.setOnCheckedChangeListener { _, enabled ->
            SettingsManager.setReverbEnabled(context, enabled)
            lifecycleScope.launch { VgmEngine.setReverbEnabled(enabled) }
        }
        binding.switchOpenPlayerOnSelection.setOnCheckedChangeListener { _, enabled ->
            SettingsManager.setOpenPlayerOnSelection(context, enabled)
        }
        binding.switchZipBrowsing.setOnCheckedChangeListener { _, enabled ->
            SettingsManager.setZipBrowsingEnabled(context, enabled)
        }
        binding.btnClearArchiveCache.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { ZipArchiveStore.clearCache(context) }
                updateCacheSize()
            }
        }
        binding.seekbarLoopRepeats.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvLoopRepeats.text = loopRepeatLabel(progress)
                if (fromUser) {
                    (activity as? org.vlessert.vgmp.MainActivity)?.getService()?.setLoopRepeats(progress)
                        ?: SettingsManager.setLoopRepeats(context, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.radioAnalyzerStyle.setOnCheckedChangeListener { _, checkedId ->
            SettingsManager.setAnalyzerStyle(
                context,
                if (checkedId == R.id.radio_bars) SettingsManager.ANALYZER_STYLE_BARS
                else SettingsManager.ANALYZER_STYLE_KALEIDOSCOPE
            )
        }
        binding.radioVisualizerAxis.setOnCheckedChangeListener { _, checkedId ->
            SettingsManager.setVisualizerAxis(
                context,
                if (checkedId == R.id.radio_axis_log) SettingsManager.VISUALIZER_AXIS_LOG
                else SettingsManager.VISUALIZER_AXIS_LINEAR
            )
        }
        binding.radioVisualizerResponse.setOnCheckedChangeListener { _, checkedId ->
            SettingsManager.setVisualizerResponse(
                context,
                if (checkedId == R.id.radio_response_balanced) SettingsManager.VISUALIZER_RESPONSE_BALANCED
                else SettingsManager.VISUALIZER_RESPONSE_RAW
            )
        }
        binding.radioVgmTiming.setOnCheckedChangeListener { _, checkedId ->
            val hz = when (checkedId) {
                R.id.radio_timing_60 -> 60
                R.id.radio_timing_50 -> 50
                else -> 0
            }
            (activity as? org.vlessert.vgmp.MainActivity)?.getService()?.setVgmPlaybackHz(hz)
                ?: SettingsManager.setVgmPlaybackHz(context, hz)
        }
        binding.seekbarVisualizerFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = progress + VisualizerRefreshRate.MIN_FPS
                binding.tvVisualizerFps.text = "$fps FPS"
                if (fromUser) SettingsManager.setVisualizerFps(context, fps)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loopRepeatLabel(repeats: Int): String = when (repeats) {
        0 -> "No repeats"
        1 -> "1 repeat"
        else -> "$repeats repeats"
    }

    private fun updateCacheSize() {
        if (_binding == null) return
        val bytes = ZipArchiveStore.cacheSize(requireContext())
        val value = when {
            bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f KiB".format(bytes / 1024.0)
        }
        binding.tvArchiveCacheSize.text = "Archive cache: $value / 512 MiB"
    }

    companion object { fun newInstance() = SettingsDialogFragment() }
}
