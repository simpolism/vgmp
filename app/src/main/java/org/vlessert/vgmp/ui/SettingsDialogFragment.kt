package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        when (SettingsManager.getAnalyzerStyle(context)) {
            SettingsManager.ANALYZER_STYLE_BARS -> binding.radioBars.isChecked = true
            else -> binding.radioKaleidoscope.isChecked = true
        }
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
        binding.radioAnalyzerStyle.setOnCheckedChangeListener { _, checkedId ->
            SettingsManager.setAnalyzerStyle(
                context,
                if (checkedId == R.id.radio_bars) SettingsManager.ANALYZER_STYLE_BARS
                else SettingsManager.ANALYZER_STYLE_KALEIDOSCOPE
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { fun newInstance() = SettingsDialogFragment() }
}
