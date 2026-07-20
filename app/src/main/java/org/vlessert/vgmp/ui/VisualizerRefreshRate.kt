package org.vlessert.vgmp.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

object VisualizerRefreshRate {
    const val MIN_FPS = 15

    fun maxSupportedFps(activity: Activity): Int {
        val display = activityDisplay(activity) ?: return 60
        val current = display.mode
        val maximum = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull(Display.Mode::getRefreshRate) ?: current.refreshRate
        return maximum.roundToInt().coerceAtLeast(MIN_FPS)
    }

    fun targetFps(activity: Activity, configuredFps: Int): Int =
        clampVisualizerFps(configuredFps, maxSupportedFps(activity))

    fun closestMode(activity: Activity, targetFps: Int): Display.Mode? {
        val display = activityDisplay(activity) ?: return null
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minWithOrNull(
                compareBy<Display.Mode> { abs(it.refreshRate - targetFps) }
                    .thenByDescending { it.refreshRate }
            )
    }

    @Suppress("DEPRECATION")
    private fun activityDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else (activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager).defaultDisplay
}

internal fun clampVisualizerFps(configuredFps: Int, maxSupportedFps: Int): Int =
    configuredFps.coerceIn(
        VisualizerRefreshRate.MIN_FPS,
        maxSupportedFps.coerceAtLeast(VisualizerRefreshRate.MIN_FPS)
    )
