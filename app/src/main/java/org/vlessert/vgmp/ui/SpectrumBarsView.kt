package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import org.vlessert.vgmp.settings.SettingsManager
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class SpectrumBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        alpha = 160
    }

    private var spectrumData: FloatArray? = null
    private var smoothed: FloatArray? = null
    private var peaks: FloatArray? = null
    private var lastDrawMs = 0L

    private val bandCount = 48
    private val binned = FloatArray(bandCount)
    private val barGap = 5f
    private val corner = 10f

    private var barRect = RectF()
    private var gradient: LinearGradient? = null

    fun updateFFT(magnitudes: FloatArray) {
        spectrumData = magnitudes
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                0xFF00F0FF.toInt(),
                0xFF00FF99.toInt(),
                0xFFFFC857.toInt(),
                0xFFFF3D7F.toInt()
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        barPaint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val magnitudes = spectrumData ?: return
        if (magnitudes.isEmpty()) return

        val now = System.currentTimeMillis()
        val dt = if (lastDrawMs == 0L) 16f else (now - lastDrawMs).coerceAtMost(50).toFloat()
        lastDrawMs = now

        val n = magnitudes.size
        val logarithmic = SettingsManager.getVisualizerAxis(context) ==
            SettingsManager.VISUALIZER_AXIS_LOG
        val balanced = SettingsManager.getVisualizerResponse(context) ==
            SettingsManager.VISUALIZER_RESPONSE_BALANCED

        // RMS pooling keeps a single narrow peak from dominating a visual band.
        val logMax = ln(n.toFloat())
        for (i in 0 until bandCount) {
            val start = if (logarithmic) {
                exp(logMax * i / bandCount).toInt()
            } else {
                1 + i * (n - 1) / bandCount
            }.coerceIn(1, n - 1)
            val end = if (logarithmic) {
                exp(logMax * (i + 1) / bandCount).toInt()
            } else {
                1 + (i + 1) * (n - 1) / bandCount
            }.coerceIn(start + 1, n)
            var sumSquares = 0f
            for (j in start until end) {
                val v = magnitudes[j]
                sumSquares += v * v
            }
            val rms = sqrt(sumSquares / (end - start))
            binned[i] = if (balanced) {
                SpectrumScale.level(rms, i.toFloat() / (bandCount - 1))
            } else {
                (rms / 255f * 0.9f).coerceIn(0f, 0.9f)
            }
        }

        if (smoothed == null || smoothed!!.size != bandCount) {
            smoothed = binned.copyOf()
            peaks = binned.copyOf()
        } else {
            // Time-based smoothing keeps the response consistent at every FPS setting.
            val attack = (1.0 - exp((-dt / 12f).toDouble())).toFloat()
            val release = (1.0 - exp((-dt / 55f).toDouble())).toFloat()
            val peakFall = 0.025f * (dt / 16f)
            for (i in 0 until bandCount) {
                val target = binned[i]
                val current = smoothed!![i]
                smoothed!![i] = if (target > current) {
                    current + (target - current) * attack
                } else {
                    current + (target - current) * release
                }
                peaks!![i] = max(peaks!![i] - peakFall, smoothed!![i])
            }
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val topInset = 24f
        val bottomInset = 12f
        val baseline = height - bottomInset
        val drawableHeight = (baseline - topInset).coerceAtLeast(1f)
        val barWidth = (width - barGap * (bandCount + 1)) / bandCount

        for (i in 0 until bandCount) {
            val normalized = smoothed!![i]
            val barHeight = max(4f, normalized * drawableHeight)
            val left = barGap + i * (barWidth + barGap)
            val top = baseline - barHeight

            barRect.set(left, top, left + barWidth, baseline)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)

            // Peak cap
            val peakY = baseline - peaks!![i] * drawableHeight
            barRect.set(left, peakY - 5f, left + barWidth, peakY)
            canvas.drawRoundRect(barRect, 4f, 4f, peakPaint)
        }
    }
}
