package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.roundToInt

/**
 * Drives a visualizer from display vsync while retaining only the latest spectrum frame.
 *
 * Spectrum delivery and drawing are intentionally independent: tying invalidation directly to
 * Flow collection makes producer stalls indistinguishable from UI/render stalls and allows
 * repeated invalidations to be coalesced. This loop makes the requested, delivered, and drawn
 * rates independently observable on-device.
 */
abstract class FrameDrivenVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private val diagnosticsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val diagnosticsBackgroundPaint = Paint().apply {
        color = 0xB0000000.toInt()
    }
    private val diagnosticsPadding = 8f * resources.displayMetrics.density
    private val diagnosticLines = arrayOf("", "", "")
    private var diagnosticsWidth = 0f
    private var diagnosticsLastUpdatedNs = 0L

    private var frameCallbackPosted = false
    private var nextDrawNs = 0L
    private var targetFps = 60
    private var reportedProducerFps = 0f

    private var inputWindowStartNs = SystemClock.elapsedRealtimeNanos()
    private var inputFrames = 0
    private var measuredInputFps = 0f
    private var drawWindowStartNs = SystemClock.elapsedRealtimeNanos()
    private var drawnFrames = 0
    private var measuredDrawFps = 0f
    private var callbackWindowStartNs = 0L
    private var callbacks = 0
    private var measuredCallbackFps = 0f

    /** Configure both the render cadence and the service-side diagnostic value. */
    fun setDiagnostics(targetFps: Int, producerFps: Float) {
        setTargetFps(targetFps)
        reportedProducerFps = producerFps
    }

    fun setTargetFps(fps: Int) {
        val clamped = fps.coerceIn(1, 240)
        if (targetFps != clamped) {
            targetFps = clamped
            nextDrawNs = 0L
        }
        applyRequestedFrameRate()
        ensureFrameCallback()
    }

    /** Call once for every spectrum value delivered to this view. */
    protected fun recordSpectrumInput() {
        inputFrames++
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = now - inputWindowStartNs
        if (elapsed >= RATE_WINDOW_NS) {
            measuredInputFps = inputFrames * NANOS_PER_SECOND / elapsed.toFloat()
            inputFrames = 0
            inputWindowStartNs = now
        }
        ensureFrameCallback()
    }

    /** Call at the beginning of the subclass's onDraw. */
    protected fun recordVisualizerDraw() {
        drawnFrames++
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = now - drawWindowStartNs
        if (elapsed >= RATE_WINDOW_NS) {
            measuredDrawFps = drawnFrames * NANOS_PER_SECOND / elapsed.toFloat()
            drawnFrames = 0
            drawWindowStartNs = now
        }
    }

    /** Call last so the diagnostic remains readable above the visualizer. */
    protected fun drawFrameDiagnostics(canvas: Canvas) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (diagnosticsLastUpdatedNs == 0L || now - diagnosticsLastUpdatedNs >= DIAGNOSTICS_INTERVAL_NS) {
            val displayFps = display?.refreshRate ?: 0f
            diagnosticLines[0] =
                "target $targetFps  display ${displayFps.roundToInt()}  vsync ${formatRate(measuredCallbackFps)}"
            diagnosticLines[1] =
                "producer ${formatRate(reportedProducerFps)}  input ${formatRate(measuredInputFps)}"
            diagnosticLines[2] = "draw ${formatRate(measuredDrawFps)}"
            diagnosticsWidth = diagnosticLines.maxOf(diagnosticsPaint::measureText)
            diagnosticsLastUpdatedNs = now
        }
        val lineHeight = diagnosticsPaint.fontSpacing
        canvas.drawRect(
            diagnosticsPadding,
            diagnosticsPadding,
            diagnosticsPadding * 3f + diagnosticsWidth,
            diagnosticsPadding * 2f + lineHeight * diagnosticLines.size,
            diagnosticsBackgroundPaint
        )
        var baseline = diagnosticsPadding * 1.5f - diagnosticsPaint.fontMetrics.top
        for (line in diagnosticLines) {
            canvas.drawText(line, diagnosticsPadding * 2f, baseline, diagnosticsPaint)
            baseline += lineHeight
        }
    }

    final override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!isAttachedToWindow || !isShown) return
        callbacks++
        if (callbackWindowStartNs == 0L) callbackWindowStartNs = frameTimeNanos
        val callbackElapsed = frameTimeNanos - callbackWindowStartNs
        if (callbackElapsed >= RATE_WINDOW_NS) {
            measuredCallbackFps = callbacks * NANOS_PER_SECOND / callbackElapsed.toFloat()
            callbacks = 0
            callbackWindowStartNs = frameTimeNanos
        }

        val intervalNs = NANOS_PER_SECOND.toLong() / targetFps
        if (nextDrawNs == 0L || frameTimeNanos >= nextDrawNs - DEADLINE_TOLERANCE_NS) {
            invalidate()
            nextDrawNs = when {
                nextDrawNs == 0L -> frameTimeNanos + intervalNs
                frameTimeNanos - nextDrawNs > intervalNs -> frameTimeNanos + intervalNs
                else -> nextDrawNs + intervalNs
            }
        }
        ensureFrameCallback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetRateWindows()
        applyRequestedFrameRate()
        ensureFrameCallback()
    }

    override fun onDetachedFromWindow() {
        if (frameCallbackPosted) Choreographer.getInstance().removeFrameCallback(this)
        frameCallbackPosted = false
        clearRequestedFrameRate()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            nextDrawNs = 0L
            resetRateWindows()
            applyRequestedFrameRate()
            ensureFrameCallback()
        } else {
            clearRequestedFrameRate()
        }
    }

    private fun ensureFrameCallback() {
        if (!frameCallbackPosted && isAttachedToWindow && isShown) {
            frameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun resetRateWindows() {
        val now = SystemClock.elapsedRealtimeNanos()
        inputWindowStartNs = now
        drawWindowStartNs = now
        callbackWindowStartNs = 0L
        inputFrames = 0
        drawnFrames = 0
        callbacks = 0
        measuredInputFps = 0f
        measuredDrawFps = 0f
        measuredCallbackFps = 0f
        diagnosticsLastUpdatedNs = 0L
    }

    private fun applyRequestedFrameRate() {
        // Android 15's adaptive-refresh-rate vote is per View. A preference on the Window or a
        // parent ViewGroup does not propagate to this animated child.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && isShown) {
            requestedFrameRate = targetFps.toFloat()
        }
    }

    private fun clearRequestedFrameRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    }

    private fun formatRate(rate: Float): String = if (rate > 0f) "%.1f".format(rate) else "--"

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val RATE_WINDOW_NS = 1_000_000_000L
        const val DIAGNOSTICS_INTERVAL_NS = 500_000_000L
        // Choreographer timestamps and integer target periods can differ by a fraction of a ms.
        const val DEADLINE_TOLERANCE_NS = 500_000L
    }
}
