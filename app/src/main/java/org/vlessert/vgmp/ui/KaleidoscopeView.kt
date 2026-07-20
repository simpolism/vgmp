package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import kotlin.math.*

class KaleidoscopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameDrivenVisualizerView(context, attrs, defStyleAttr) {

    private var spectrumData: FloatArray? = null
    private var rotationAngle = 0f
    private var lastDrawMs = 0L
    // The wedges meet edge-to-edge, so antialiasing each of the hundreds of paths only adds
    // overdraw. Keeping this paint allocation-free makes high-refresh rendering practical.
    private val paint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF00FF")
        alpha = 45
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    init {
        // Make the view clickable to receive touch events
        isClickable = true
    }
    
    // Smoothing
    private val smoothingFactor = 0.7f
    private val binCount = 56
    private val binned = FloatArray(binCount)
    private val smoothedMagnitudes = FloatArray(binCount)
    private val wedgePaths = Array(binCount / 2) { Path() }
    private var smoothingInitialized = false
    
    // Kaleidoscope segments
    private val segments = 12
    
    // Colors for the spectrum
    private val colors = intArrayOf(
        Color.parseColor("#FF0066"),  // Magenta
        Color.parseColor("#FF6600"),  // Orange
        Color.parseColor("#FFCC00"),  // Yellow
        Color.parseColor("#66FF00"),  // Lime
        Color.parseColor("#00FFCC"),  // Cyan
        Color.parseColor("#0066FF"),  // Blue
        Color.parseColor("#6600FF"),  // Purple
        Color.parseColor("#FF00CC"),  // Pink
        Color.parseColor("#FF3366"),  // Red-pink
        Color.parseColor("#FF9933"),  // Orange
        Color.parseColor("#CCFF00"),  // Yellow-green
        Color.parseColor("#00FF99")   // Green-cyan
    )
    
    fun updateFFT(magnitudes: FloatArray) {
        spectrumData = magnitudes
        recordSpectrumInput()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val centerRadius = min(w, h) * 0.075f
        innerPaint.shader = RadialGradient(
            w / 2f, h / 2f, centerRadius,
            intArrayOf(Color.parseColor("#FF00FF"), Color.parseColor("#00FFFF")),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        recordVisualizerDraw()
        
        val magnitudes = spectrumData ?: return
        if (magnitudes.isEmpty()) return

        val now = System.currentTimeMillis()
        val dt = if (lastDrawMs == 0L) 16f else (now - lastDrawMs).coerceAtMost(50).toFloat()
        lastDrawMs = now
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = min(width, height) / 2f
        
        // Process magnitudes into bins
        val n = magnitudes.size
        
        for (i in 0 until binCount) {
            val startIdx = (i * n / binCount).coerceIn(0, n - 1)
            val endIdx = ((i + 1) * n / binCount).coerceIn(0, n)
            var sum = 0f
            var count = 0
            for (j in startIdx until endIdx) {
                sum += magnitudes[j]
                count++
            }
            binned[i] = if (count > 0) sum / count else 0f
        }
        
        // Smoothing
        if (!smoothingInitialized) {
            binned.copyInto(smoothedMagnitudes)
            smoothingInitialized = true
        } else {
            for (i in 0 until binCount) {
                val current = smoothedMagnitudes[i]
                val target = binned[i]
                val timeConstantMs = if (target > current) 20f else 72f
                val alpha = (1.0 - exp((-dt / timeConstantMs).toDouble())).toFloat()
                smoothedMagnitudes[i] = current + (target - current) * alpha
            }
        }
        
        // Rotate slowly
        rotationAngle += 0.03125f * dt
        if (rotationAngle >= 360f) rotationAngle -= 360f
        
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        
        // Draw kaleidoscope segments
        val angleStep = 360f / segments
        val innerRadius = maxRadius * 0.2f
        val barsPerSegment = binCount / 2

        // Build each unique wedge once. Earlier this geometry was rebuilt 24 times per frame
        // (once for every rotated/mirrored copy), dominating the UI thread at high refresh rates.
        for (i in 0 until barsPerSegment) {
            val normalizedMag = (smoothedMagnitudes[i] / 128f).coerceIn(0f, 1f)
            val outerRadius = innerRadius + (maxRadius - innerRadius) * normalizedMag
            val angle1 = Math.toRadians((i * angleStep / barsPerSegment).toDouble())
            val angle2 = Math.toRadians(((i + 1) * angleStep / barsPerSegment).toDouble())
            val cos1 = cos(angle1).toFloat()
            val sin1 = sin(angle1).toFloat()
            val cos2 = cos(angle2).toFloat()
            val sin2 = sin(angle2).toFloat()
            wedgePaths[i].apply {
                reset()
                moveTo(centerX + innerRadius * cos1, centerY + innerRadius * sin1)
                lineTo(centerX + outerRadius * cos1, centerY + outerRadius * sin1)
                lineTo(centerX + outerRadius * cos2, centerY + outerRadius * sin2)
                lineTo(centerX + innerRadius * cos2, centerY + innerRadius * sin2)
                close()
            }
        }
        
        for (seg in 0 until segments) {
            val baseAngle = seg * angleStep
            
            canvas.save()
            canvas.rotate(baseAngle, centerX, centerY)
            
            // Draw mirrored segment
            for (mirror in 0..1) {
                if (mirror == 1) {
                    canvas.scale(1f, -1f, centerX, centerY)
                }
                
                // Draw radial bars
                for (i in 0 until barsPerSegment) {
                    val normalizedMag = (smoothedMagnitudes[i] / 128f).coerceIn(0f, 1f)
                    
                    if (normalizedMag < 0.05f) continue
                    
                    // Create gradient color based on position
                    val colorIndex = (i + seg) % colors.size
                    val alpha = (200 + 55 * normalizedMag).toInt().coerceIn(0, 255)
                    paint.color = colors[colorIndex]
                    paint.alpha = alpha
                    
                    canvas.drawPath(wedgePaths[i], paint)
                }
            }
            
            canvas.restore()
        }
        
        canvas.restore()
        
        // Draw center circle with glow effect
        val centerRadius = maxRadius * 0.15f
        var totalMagnitude = 0f
        for (magnitude in smoothedMagnitudes) totalMagnitude += magnitude
        val avgMag = totalMagnitude / smoothedMagnitudes.size
        val pulseRadius = centerRadius + centerRadius * 0.3f * (avgMag / 128f).coerceIn(0f, 1f)
        
        // Glow effect
        // A translucent halo stays on the hardware path. BlurMaskFilter made this fullscreen
        // view dramatically more expensive on some GPUs.
        canvas.drawCircle(centerX, centerY, pulseRadius * 1.35f, glowPaint)
        
        // Inner circle
        canvas.drawCircle(centerX, centerY, centerRadius, innerPaint)
        drawFrameDiagnostics(canvas)
    }
}
