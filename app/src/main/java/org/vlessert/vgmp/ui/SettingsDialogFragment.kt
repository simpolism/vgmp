package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentSettingsBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.settings.SettingsManager

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
        binding.switchBassEnabled.isChecked = SettingsManager.isBassEnabled(context)
        binding.switchReverbEnabled.isChecked = SettingsManager.isReverbEnabled(context)
        binding.switchOpenPlayerOnSelection.isChecked = SettingsManager.openPlayerOnSelection(context)
        binding.switchZipBrowsing.isChecked = SettingsManager.isZipBrowsingEnabled(context)
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
        val fps = SettingsManager.getVisualizerFps(context)
        binding.seekbarVisualizerFps.progress = fps - 15
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
        binding.seekbarVisualizerFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = progress + 15
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

    companion object { fun newInstance() = SettingsDialogFragment() }
}
