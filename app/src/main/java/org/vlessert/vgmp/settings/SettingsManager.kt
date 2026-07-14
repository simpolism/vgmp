package org.vlessert.vgmp.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "vgmp_settings"
    private const val KEY_ANALYZER_STYLE = "analyzer_style"
    private const val KEY_VISUALIZER_AXIS = "visualizer_axis"
    private const val KEY_VISUALIZER_RESPONSE = "visualizer_response"
    private const val KEY_VISUALIZER_FPS = "visualizer_fps"
    private const val KEY_OPEN_PLAYER_ON_SELECTION = "open_player_on_selection"
    private const val KEY_ZIP_BROWSING = "zip_browsing"
    private const val KEY_BASS_ENABLED = "bass_enabled"
    private const val KEY_REVERB_ENABLED = "reverb_enabled"
    private const val KEY_VGM_PLAYBACK_HZ = "vgm_playback_hz"
    private const val KEY_LOOP_REPEATS = "embedded_loop_repeats"
    private const val KEY_CHIP_VOLUME_PREFIX = "chip_volume_"

    const val ANALYZER_STYLE_KALEIDOSCOPE = "kaleidoscope"
    const val ANALYZER_STYLE_BARS = "bars"
    const val VISUALIZER_AXIS_LINEAR = "linear"
    const val VISUALIZER_AXIS_LOG = "log"
    const val VISUALIZER_RESPONSE_RAW = "raw"
    const val VISUALIZER_RESPONSE_BALANCED = "balanced"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getAnalyzerStyle(context: Context): String {
        return getPrefs(context).getString(KEY_ANALYZER_STYLE, ANALYZER_STYLE_KALEIDOSCOPE)
            ?: ANALYZER_STYLE_KALEIDOSCOPE
    }

    fun setAnalyzerStyle(context: Context, style: String) {
        getPrefs(context).edit().putString(KEY_ANALYZER_STYLE, style).apply()
    }

    fun getVisualizerAxis(context: Context): String =
        getPrefs(context).getString(KEY_VISUALIZER_AXIS, VISUALIZER_AXIS_LINEAR)
            ?: VISUALIZER_AXIS_LINEAR
    fun setVisualizerAxis(context: Context, value: String) =
        getPrefs(context).edit().putString(KEY_VISUALIZER_AXIS, value).apply()

    fun getVisualizerResponse(context: Context): String =
        getPrefs(context).getString(KEY_VISUALIZER_RESPONSE, VISUALIZER_RESPONSE_RAW)
            ?: VISUALIZER_RESPONSE_RAW
    fun setVisualizerResponse(context: Context, value: String) =
        getPrefs(context).edit().putString(KEY_VISUALIZER_RESPONSE, value).apply()

    fun getVisualizerFps(context: Context): Int =
        getPrefs(context).getInt(KEY_VISUALIZER_FPS, 42).coerceIn(15, 240)
    fun setVisualizerFps(context: Context, value: Int) =
        getPrefs(context).edit().putInt(KEY_VISUALIZER_FPS, value.coerceIn(15, 240)).apply()

    fun openPlayerOnSelection(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_OPEN_PLAYER_ON_SELECTION, false)
    fun setOpenPlayerOnSelection(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_OPEN_PLAYER_ON_SELECTION, enabled).apply()

    fun isZipBrowsingEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ZIP_BROWSING, true)
    fun setZipBrowsingEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_ZIP_BROWSING, enabled).apply()

    fun isBassEnabled(context: Context) = getPrefs(context).getBoolean(KEY_BASS_ENABLED, false)
    fun setBassEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_BASS_ENABLED, enabled).apply()

    fun isReverbEnabled(context: Context) = getPrefs(context).getBoolean(KEY_REVERB_ENABLED, false)
    fun setReverbEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_REVERB_ENABLED, enabled).apply()

    fun getVgmPlaybackHz(context: Context): Int =
        normalizeVgmPlaybackHz(getPrefs(context).getInt(KEY_VGM_PLAYBACK_HZ, 0))

    fun setVgmPlaybackHz(context: Context, hz: Int) =
        getPrefs(context).edit().putInt(KEY_VGM_PLAYBACK_HZ, normalizeVgmPlaybackHz(hz)).apply()

    fun getLoopRepeats(context: Context): Int =
        normalizeLoopRepeats(getPrefs(context).getInt(KEY_LOOP_REPEATS, 0))

    fun setLoopRepeats(context: Context, repeats: Int) =
        getPrefs(context).edit().putInt(KEY_LOOP_REPEATS, normalizeLoopRepeats(repeats)).apply()

    private fun chipKey(chipName: String) = KEY_CHIP_VOLUME_PREFIX + chipName.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "_")

    fun getChipVolume(context: Context, chipName: String, defaultValue: Int): Int =
        getPrefs(context).getInt(chipKey(chipName), defaultValue)

    fun setChipVolume(context: Context, chipName: String, volume: Int) =
        getPrefs(context).edit().putInt(chipKey(chipName), volume).apply()
}

internal fun normalizeVgmPlaybackHz(hz: Int): Int = hz.takeIf { it == 50 || it == 60 } ?: 0

internal fun normalizeLoopRepeats(repeats: Int): Int = repeats.coerceIn(0, 10)

internal fun nextVgmPlaybackHz(hz: Int): Int = when (normalizeVgmPlaybackHz(hz)) {
    0 -> 60
    60 -> 50
    else -> 0
}
