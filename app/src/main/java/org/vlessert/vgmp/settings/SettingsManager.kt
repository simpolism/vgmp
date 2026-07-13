package org.vlessert.vgmp.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "vgmp_settings"
    private const val KEY_ANALYZER_ENABLED = "analyzer_enabled"
    private const val KEY_ANALYZER_STYLE = "analyzer_style"
    private const val KEY_BASS_ENABLED = "bass_enabled"
    private const val KEY_REVERB_ENABLED = "reverb_enabled"
    private const val KEY_CHIP_VOLUME_PREFIX = "chip_volume_"

    const val ANALYZER_STYLE_KALEIDOSCOPE = "kaleidoscope"
    const val ANALYZER_STYLE_BARS = "bars"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun isAnalyzerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ANALYZER_ENABLED, true)
    }
    
    fun setAnalyzerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ANALYZER_ENABLED, enabled).apply()
    }
    
    fun getAnalyzerStyle(context: Context): String {
        return getPrefs(context).getString(KEY_ANALYZER_STYLE, ANALYZER_STYLE_KALEIDOSCOPE)
            ?: ANALYZER_STYLE_KALEIDOSCOPE
    }

    fun setAnalyzerStyle(context: Context, style: String) {
        getPrefs(context).edit().putString(KEY_ANALYZER_STYLE, style).apply()
    }

    fun isBassEnabled(context: Context) = getPrefs(context).getBoolean(KEY_BASS_ENABLED, false)
    fun setBassEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_BASS_ENABLED, enabled).apply()

    fun isReverbEnabled(context: Context) = getPrefs(context).getBoolean(KEY_REVERB_ENABLED, false)
    fun setReverbEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_REVERB_ENABLED, enabled).apply()

    private fun chipKey(chipName: String) = KEY_CHIP_VOLUME_PREFIX + chipName.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "_")

    fun getChipVolume(context: Context, chipName: String, defaultValue: Int): Int =
        getPrefs(context).getInt(chipKey(chipName), defaultValue)

    fun setChipVolume(context: Context, chipName: String, volume: Int) =
        getPrefs(context).edit().putInt(chipKey(chipName), volume).apply()
}
