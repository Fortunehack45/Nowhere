package com.fakegps.mocklocation.ui.custom

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import com.fakegps.mocklocation.util.FrostedGlassManager
import com.google.android.material.card.MaterialCardView

/**
 * Material card that samples the map (or any backdrop view) behind it, downsamples,
 * blurs, and paints that as a true frosted-glass plate instead of a flat translucent wash.
 */
class FrostedGlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    var blurSource: View? = null
        set(value) {
            field = value
            scheduleBlurCapture()
        }

    private var blurBitmap: Bitmap? = null
    private var lastCaptureAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()
    private val destRect = RectF()

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        maybeCapture()
        true
    }

    init {
        clipToOutline = true
        setWillNotDraw(false)
        // Solid XML fills hide the blurred map; the tint paint supplies the frost wash.
        setCardBackgroundColor(Color.TRANSPARENT)
        cardElevation = 0f
        tintPaint.color = FrostedGlassManager.glassTintColor(context)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setCardBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setCardBackgroundColor(Color.TRANSPARENT)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        tintPaint.color = FrostedGlassManager.glassTintColor(context)
    }

    override fun onDetachedFromWindow() {
        try {
            viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        } catch (_: Exception) {
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastCaptureAt = 0L
        scheduleBlurCapture()
    }

    private fun scheduleBlurCapture() {
        if (isLaidOut) {
            lastCaptureAt = 0L
            maybeCapture()
            invalidate()
        } else {
            post {
                lastCaptureAt = 0L
                maybeCapture()
                invalidate()
            }
        }
    }

    private fun maybeCapture() {
        val source = blurSource ?: return
        if (width <= 0 || height <= 0) return
        if (!source.isLaidOut) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < FrostedGlassManager.CAPTURE_INTERVAL_MS) return
        lastCaptureAt = now
        blurBitmap = FrostedGlassManager.captureBlurredRegion(source, this)
        tintPaint.color = FrostedGlassManager.glassTintColor(context)
    }

    override fun draw(canvas: Canvas) {
        val bmp = blurBitmap
        if (bmp != null && !bmp.isRecycled) {
            destRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val save = canvas.save()
            canvas.clipPath(roundedClipPath())
            canvas.drawBitmap(bmp, null, destRect, paint)
            canvas.drawRect(destRect, tintPaint)
            canvas.restoreToCount(save)
        }
        super.draw(canvas)
    }

    private fun roundedClipPath(): Path {
        val path = Path()
        val r = radius
        path.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
        return path
    }
}
