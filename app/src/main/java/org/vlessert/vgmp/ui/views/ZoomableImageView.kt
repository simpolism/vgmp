package org.vlessert.vgmp.ui.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val drawableBounds = RectF()
    private var userScale = 1f
    private var matrixReady = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMoved = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextScale = (userScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val appliedScale = nextScale / userScale
                userScale = nextScale
                drawMatrix.postScale(
                    appliedScale,
                    appliedScale,
                    detector.focusX,
                    detector.focusY
                )
                constrainMatrix()
                imageMatrix = drawMatrix
                invalidate()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetImageMatrix()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (!matrixReady) resetImageMatrix()
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (!scaleDetector.isInProgress && userScale > MIN_SCALE) {
                    drawMatrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = drawMatrix
                    invalidate()
                }
                if (abs(dx) > 2f || abs(dy) > 2f) touchMoved = true
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!touchMoved) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resetImageMatrix() {
        val source = drawable ?: return
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0 ||
            source.intrinsicWidth <= 0 || source.intrinsicHeight <= 0
        ) {
            return
        }

        val fitScale = min(
            contentWidth.toFloat() / source.intrinsicWidth,
            contentHeight.toFloat() / source.intrinsicHeight
        )
        val scaledWidth = source.intrinsicWidth * fitScale
        val scaledHeight = source.intrinsicHeight * fitScale
        val left = paddingLeft + (contentWidth - scaledWidth) / 2f
        val top = paddingTop + (contentHeight - scaledHeight) / 2f

        drawMatrix.reset()
        drawMatrix.postScale(fitScale, fitScale)
        drawMatrix.postTranslate(left, top)
        imageMatrix = drawMatrix
        userScale = MIN_SCALE
        matrixReady = true
        invalidate()
    }

    private fun constrainMatrix() {
        val source = drawable ?: return
        drawableBounds.set(
            0f,
            0f,
            source.intrinsicWidth.toFloat(),
            source.intrinsicHeight.toFloat()
        )
        drawMatrix.mapRect(drawableBounds)

        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentRight = (width - paddingRight).toFloat()
        val contentBottom = (height - paddingBottom).toFloat()
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop

        val dx = when {
            drawableBounds.width() <= contentWidth ->
                contentLeft + (contentWidth - drawableBounds.width()) / 2f - drawableBounds.left
            drawableBounds.left > contentLeft -> contentLeft - drawableBounds.left
            drawableBounds.right < contentRight -> contentRight - drawableBounds.right
            else -> 0f
        }
        val dy = when {
            drawableBounds.height() <= contentHeight ->
                contentTop + (contentHeight - drawableBounds.height()) / 2f - drawableBounds.top
            drawableBounds.top > contentTop -> contentTop - drawableBounds.top
            drawableBounds.bottom < contentBottom -> contentBottom - drawableBounds.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}
