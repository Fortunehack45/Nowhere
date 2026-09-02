package com.fakegps.mocklocation.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Premium Red & White Radar-Style Joystick View for Nowhere.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnJoystickMoveListener {
        fun onJoystickMoved(angleDegrees: Float, magnitude: Float)
    }

    private var listener: OnJoystickMoveListener? = null

    fun setOnJoystickMoveListener(listener: OnJoystickMoveListener?) {
        this.listener = listener
    }

    // Paint definitions
    private val baseGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E61C1C1E") // Apple dark system background
    }

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#38383A") // Minimal hairline border
    }

    private val innerRingsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1AFFFFFF") // Subtle minimal ring
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#26FFFFFF")
    }

    private val vectorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#CCE41B1B") // Red vector trail
    }

    private val cardinalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val knobOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E2738")
    }

    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#FFFFFF") // Crisp white ring
    }

    private val knobCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E41B1B") // Red core
    }

    init {
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
        setJoystickColor(primaryColor)
    }

    fun setJoystickColor(primaryColor: Int) {
        knobCorePaint.color = primaryColor
        val alpha = (0.8f * 255).toInt()
        val r = Color.red(primaryColor)
        val g = Color.green(primaryColor)
        val b = Color.blue(primaryColor)
        vectorLinePaint.color = Color.argb(alpha, r, g, b)
        invalidate()
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var maxTravelRadius = 0f

    private var knobX = 0f
    private var knobY = 0f
    private var isDragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.88f
        knobRadius = baseRadius * 0.32f
        maxTravelRadius = baseRadius - knobRadius

        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Base Glass
        canvas.drawCircle(centerX, centerY, baseRadius, baseGlassPaint)

        // Draw Concentric Radar Rings
        canvas.drawCircle(centerX, centerY, baseRadius, outerRingPaint)
        canvas.drawCircle(centerX, centerY, baseRadius * 0.66f, innerRingsPaint)
        canvas.drawCircle(centerX, centerY, baseRadius * 0.33f, innerRingsPaint)

        // Crosshairs
        canvas.drawLine(centerX - baseRadius * 0.9f, centerY, centerX + baseRadius * 0.9f, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - baseRadius * 0.9f, centerX, centerY + baseRadius * 0.9f, crosshairPaint)

        // Cardinal Directions
        canvas.drawText("N", centerX, centerY - baseRadius + 24f, cardinalTextPaint)
        canvas.drawText("S", centerX, centerY + baseRadius - 10f, cardinalTextPaint)
        canvas.drawText("E", centerX + baseRadius - 16f, centerY + 8f, cardinalTextPaint)
        canvas.drawText("W", centerX - baseRadius + 16f, centerY + 8f, cardinalTextPaint)

        // Vector line from center to knob when dragging
        if (isDragging) {
            canvas.drawLine(centerX, centerY, knobX, knobY, vectorLinePaint)
        }

        // Draw Knob
        canvas.drawCircle(knobX, knobY, knobRadius, knobOuterPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobRingPaint)
        canvas.drawCircle(knobX, knobY, knobRadius * 0.45f, knobCorePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (maxTravelRadius <= 0.1f) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isDragging = true
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = sqrt(dx * dx + dy * dy)

                val clampedDist = min(distance, maxTravelRadius)
                val angleRad = atan2(dy.toDouble(), dx.toDouble())

                val newKnobX = (centerX + clampedDist * cos(angleRad)).toFloat()
                val newKnobY = (centerY + clampedDist * sin(angleRad)).toFloat()

                if (!newKnobX.isNaN() && !newKnobY.isNaN()) {
                    knobX = newKnobX
                    knobY = newKnobY
                }

                val compassRad = atan2(dx.toDouble(), -dy.toDouble())
                val compassDeg = ((Math.toDegrees(compassRad) + 360.0) % 360.0).toFloat()
                val magnitude = (clampedDist / maxTravelRadius).coerceIn(0f, 1f)

                if (!compassDeg.isNaN() && !magnitude.isNaN()) {
                    listener?.onJoystickMoved(compassDeg, magnitude)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                knobX = centerX
                knobY = centerY
                listener?.onJoystickMoved(0f, 0f)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
