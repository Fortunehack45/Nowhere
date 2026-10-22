package com.fakegps.mocklocation.ui.custom

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.util.ThemeColorManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Premium Liquid Glass Navigation Bar Group inspired by iOS 26 / Vibra_Music
 * (FloatingBottomBar.kt & GlassSelectionPill.kt).
 *
 * Extends RadioGroup for 100% drop-in backward compatibility with existing RadioButtons,
 * CheckedChangeListeners, and ViewBinding.
 *
 * Features:
 * - Floating capsule liquid glass track with specular hairline rim.
 * - Interactive traveling liquid selection lens:
 *   * Real-time finger follow with low-pass tracking.
 *   * Directional acceleration squash & stretch (volume conservation).
 *   * Lift elevation on touch/drag with soft ambient drop shadow.
 *   * Chromatic dispersion prism edge (spectral rainbow refraction around lens rim).
 *   * Specular convex top glare for 3D liquid depth.
 *   * Boundary resistance (0.25x rubber-band damping past outer tabs).
 *   * Tactile haptic ticks when dragging across tab boundaries.
 *   * Under-damped spring settle on release (stiffness 220, damping 0.84 equivalent).
 *   * Automatic self-healing state synchronization with RadioGroup checked button.
 */
class LiquidGlassNavGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RadioGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val clickSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Physics Tuning Parameters from Vibra_Music
    private val stretchMax = 0.12f
    private val squashRatio = 0.35f
    private val boundaryResistance = 0.25f

    // Drawing Paints & Rects
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val lensShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38000000")
        style = Paint.Style.FILL
    }

    private val lensFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensSpecularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensPrismPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val lensRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val trackRect = RectF()
    private val lensRect = RectF()
    private val scaledLensRect = RectF()
    private val shadowRect = RectF()

    // State Variables
    private var primaryColor: Int = ThemeColorManager.getPrimaryColor(context)
    private var darkColor: Int = ThemeColorManager.getDarkColor(context)

    private var currentPosition: Float = 0f
    private var selectedIndex: Int = 0

    private var isDragging: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var totalDragX: Float = 0f
    private var dragOffsetPx: Float = 0f
    private var lastHapticTab: Int = 0

    // Dynamic animation parameters
    private var lift: Float = 0f // 0.0 at rest, 1.0 lifted
    private var lag: Float = 0f  // Motion lag driving squash & stretch

    private var travelAnimator: ValueAnimator? = null
    private var liftAnimator: ValueAnimator? = null

    // Listener wrapper to intercept both user interaction and programmatic checks
    private var externalCheckedChangeListener: OnCheckedChangeListener? = null
    private var isInternalChecking = false

    init {
        setWillNotDraw(false)
        orientation = HORIZONTAL
        clipToPadding = false
        clipChildren = false

        updateColors()
    }

    fun setThemeColor(primary: Int, dark: Int) {
        primaryColor = primary
        darkColor = dark
        updateColors()
        invalidate()
    }

    private fun isDarkMode(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateColors() {
        val darkMode = isDarkMode()
        val strokeColor = if (darkMode) {
            Color.parseColor("#45FFFFFF")
        } else {
            Color.parseColor("#55CBD5E1")
        }
        trackStrokePaint.color = strokeColor
    }

    override fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        externalCheckedChangeListener = listener
        super.setOnCheckedChangeListener { group, checkedId ->
            if (!isInternalChecking) {
                syncToCheckedId(checkedId, animate = isLaidOut && width > 0)
            }
            externalCheckedChangeListener?.onCheckedChanged(group, checkedId)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        cleanChildRadioButtons()
        syncSelectedIndexWithCheckedButton()
        updateChildScales(selectedIndex)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cleanChildRadioButtons()
        syncSelectedIndexWithCheckedButton()
        updateChildScales(selectedIndex)
    }

    private fun cleanChildRadioButtons() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is RadioButton) {
                child.background = null
                child.buttonDrawable = null
            }
        }
    }

    override fun check(id: Int) {
        super.check(id)
        syncToCheckedId(id, animate = isLaidOut && width > 0)
    }

    private fun syncToCheckedId(checkedId: Int, animate: Boolean) {
        val newIndex = findIndexOfChildId(checkedId)
        if (newIndex in 0 until childCount && newIndex != selectedIndex) {
            if (animate && width > 0 && isLaidOut) {
                animateToTab(newIndex, notifyRadioGroup = false)
            } else {
                travelAnimator?.cancel()
                selectedIndex = newIndex
                currentPosition = newIndex.toFloat()
                lastHapticTab = newIndex
                updateChildScales(newIndex)
                postInvalidateOnAnimation()
            }
        }
    }

    private fun syncSelectedIndexWithCheckedButton() {
        val checkedId = checkedRadioButtonId
        if (checkedId != -1) {
            val idx = findIndexOfChildId(checkedId)
            if (idx in 0 until childCount) {
                selectedIndex = idx
                currentPosition = idx.toFloat()
                lastHapticTab = idx
            }
        }
    }

    private fun findIndexOfChildId(id: Int): Int {
        for (i in 0 until childCount) {
            if (getChildAt(i).id == id) return i
        }
        return -1
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        cleanChildRadioButtons()
        syncSelectedIndexWithCheckedButton()
        updateChildScales(selectedIndex)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || childCount == 0 || getChildAt(0).right <= 0) {
            super.onDraw(canvas)
            return
        }

        // Self-healing synchronization: if checked button differs and no animator/drag active
        val currentCheckedId = checkedRadioButtonId
        if (currentCheckedId != -1 && !isDragging && travelAnimator?.isRunning != true) {
            val targetIdx = findIndexOfChildId(currentCheckedId)
            if (targetIdx in 0 until childCount && targetIdx != selectedIndex) {
                selectedIndex = targetIdx
                currentPosition = targetIdx.toFloat()
                lastHapticTab = targetIdx
                updateChildScales(targetIdx)
            }
        }

        val cornerRadius = h / 2f
        val darkMode = isDarkMode()

        // 1. Draw Outer Capsule Liquid Glass Track
        trackRect.set(1f * density, 1f * density, w - 1f * density, h - 1f * density)
        val startColor = if (darkMode) Color.parseColor("#331E202C") else Color.parseColor("#E6F4F5F8")
        val endColor = if (darkMode) Color.parseColor("#4D12141F") else Color.parseColor("#CCFFFFFF")
        trackPaint.shader = LinearGradient(0f, 0f, 0f, h, startColor, endColor, Shader.TileMode.CLAMP)

        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackStrokePaint)

        // 2. Compute Floating Selection Lens Bounds
        computeLensRect(lensRect)

        // Directional Squash & Stretch based on lag & drag
        val scaleX = 1.0f + (lag * stretchMax).coerceIn(0f, 0.20f)
        val scaleY = 1.0f - (lag * stretchMax * squashRatio).coerceIn(0f, 0.10f)

        val cx = lensRect.centerX()
        val cy = lensRect.centerY()
        val halfW = (lensRect.width() / 2f) * scaleX
        val liftHeightExpansion = lift * 3.5f * density
        val halfH = (lensRect.height() / 2f) * scaleY + liftHeightExpansion

        scaledLensRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        val lensCorner = scaledLensRect.height() / 2f

        // 3. Draw Soft Elevated Drop Shadow when Lifted
        if (lift > 0.01f) {
            val shadowOffsetY = lift * 5.0f * density
            shadowRect.set(
                scaledLensRect.left + 1f * density,
                scaledLensRect.top + shadowOffsetY,
                scaledLensRect.right - 1f * density,
                scaledLensRect.bottom + shadowOffsetY
            )
            lensShadowPaint.alpha = (lift * 75).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(shadowRect, lensCorner, lensCorner, lensShadowPaint)
        }

        // 4. Draw Traveling Liquid Glass Lens Body (Translucent Refraction Fill)
        val topAlpha = if (darkMode) 0.52f else 0.42f
        val bottomAlpha = if (darkMode) 0.68f else 0.58f
        val lensTopColor = adjustAlpha(primaryColor, topAlpha)
        val lensBottomColor = adjustAlpha(darkColor, bottomAlpha)
        lensFillPaint.shader = LinearGradient(
            scaledLensRect.left, scaledLensRect.top,
            scaledLensRect.left, scaledLensRect.bottom,
            lensTopColor, lensBottomColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(scaledLensRect, lensCorner, lensCorner, lensFillPaint)

        // 5. Draw 3D Convex Top Glare Sheen (Lens Curvature)
        lensSpecularPaint.shader = LinearGradient(
            scaledLensRect.left, scaledLensRect.top,
            scaledLensRect.left, scaledLensRect.top + scaledLensRect.height() * 0.58f,
            intArrayOf(
                Color.parseColor("#7AFFFFFF"),
                Color.parseColor("#25FFFFFF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.38f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(scaledLensRect, lensCorner, lensCorner, lensSpecularPaint)

        // 6. Draw Chromatic Dispersion Prism Rim (Spectral Rainbow Refraction)
        lensPrismPaint.shader = LinearGradient(
            scaledLensRect.left, scaledLensRect.top,
            scaledLensRect.right, scaledLensRect.bottom,
            intArrayOf(
                Color.parseColor("#8000E5FF"), // Cyan
                Color.parseColor("#9000FF88"), // Mint
                Color.parseColor("#90FFD600"), // Amber Gold
                Color.parseColor("#90FF4081"), // Magenta
                Color.parseColor("#807C4DFF")  // Violet
            ),
            floatArrayOf(0.0f, 0.22f, 0.50f, 0.78f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(scaledLensRect, lensCorner, lensCorner, lensPrismPaint)

        // 7. Draw Hairline Specular Outer Rim (Reflective Glass Bevel)
        lensRimPaint.shader = LinearGradient(
            scaledLensRect.left, scaledLensRect.top,
            scaledLensRect.left, scaledLensRect.bottom,
            Color.parseColor("#B3FFFFFF"),
            Color.parseColor("#33FFFFFF"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(scaledLensRect, lensCorner, lensCorner, lensRimPaint)

        // 8. Super call draws Child RadioButton Texts & Icons cleanly above the lens
        super.onDraw(canvas)
    }

    private fun computeLensRect(outRect: RectF) {
        if (childCount == 0) return

        val n = childCount
        val pos = (currentPosition + dragOffsetPxToPosDelta()).coerceIn(-0.35f, (n - 1) + 0.35f)

        val baseIndex = pos.toInt().coerceIn(0, n - 1)
        val fraction = (pos - baseIndex).coerceIn(0f, 1f)

        val currentChild = getChildAt(baseIndex)
        val nextChild = if (baseIndex + 1 < n) getChildAt(baseIndex + 1) else currentChild

        val padX = 2f * density
        val padY = 2f * density
        val left = (currentChild.left + (nextChild.left - currentChild.left) * fraction) + padX
        val right = (currentChild.right + (nextChild.right - currentChild.right) * fraction) - padX
        val top = (currentChild.top + (nextChild.top - currentChild.top) * fraction) + padY
        val bottom = (currentChild.bottom + (nextChild.bottom - currentChild.bottom) * fraction) - padY

        outRect.set(left, top, right, bottom)
    }

    private fun dragOffsetPxToPosDelta(): Float {
        if (width <= 0 || childCount == 0) return 0f
        val stepPx = width.toFloat() / childCount
        return dragOffsetPx / stepPx
    }

    // Touch & Drag Handling with Vibra_Music Fluid Motion Physics
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                totalDragX = 0f
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                startLiftAnimation(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > clickSlopPx && dx > dy) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    startLiftAnimation(false)
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                totalDragX = 0f
                isDragging = false
                startLiftAnimation(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = abs(event.y - downY)
                totalDragX = dx

                if (!isDragging && abs(dx) > clickSlopPx && abs(dx) > dy) {
                    isDragging = true
                    travelAnimator?.cancel()
                }

                if (isDragging) {
                    val stepPx = (width.toFloat() / childCount.coerceAtLeast(1))
                    val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val rawOffset = totalDragX

                    // Boundary rubber-band resistance
                    val stepDelta = if (isRtl) -rawOffset / stepPx else rawOffset / stepPx
                    val wouldBePos = selectedIndex + stepDelta
                    val effectiveOffset = when {
                        wouldBePos < 0f -> -(abs(selectedIndex * stepPx) + (abs(rawOffset) - (selectedIndex * stepPx)).coerceAtLeast(0f) * boundaryResistance)
                        wouldBePos > (childCount - 1) -> {
                            val maxReach = (childCount - 1 - selectedIndex) * stepPx
                            maxReach + (rawOffset - maxReach).coerceAtLeast(0f) * boundaryResistance
                        }
                        else -> rawOffset
                    }

                    dragOffsetPx = effectiveOffset

                    // Calculate motion lag for directional squash & stretch
                    lag = (abs(effectiveOffset) / stepPx).coerceIn(0f, 1.0f)

                    // Check tab boundary crossing for haptic ticks
                    val currentStepDelta = if (isRtl) -dragOffsetPx / stepPx else dragOffsetPx / stepPx
                    val approxTab = (selectedIndex + currentStepDelta).roundToInt().coerceIn(0, childCount - 1)
                    if (approxTab != lastHapticTab) {
                        try {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (_: Exception) {}
                        lastHapticTab = approxTab
                    }

                    postInvalidateOnAnimation()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val stepPx = (width.toFloat() / childCount.coerceAtLeast(1))
                val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL

                if (isDragging) {
                    // Fluid release snap calculation matching Vibra_Music
                    val rawRatio = totalDragX / stepPx
                    val ratio = if (isRtl) -rawRatio else rawRatio
                    val shift = when {
                        ratio > 0.35f -> kotlin.math.max(1, ratio.roundToInt())
                        ratio < -0.35f -> kotlin.math.min(-1, ratio.roundToInt())
                        else -> 0
                    }
                    val targetTab = (selectedIndex + shift).coerceIn(0, childCount - 1)

                    // Seamless transition: transfer current offset position to currentPosition before resetting dragOffsetPx
                    val currentLensPos = (currentPosition + dragOffsetPxToPosDelta()).coerceIn(0f, (childCount - 1).toFloat())
                    currentPosition = currentLensPos
                    dragOffsetPx = 0f

                    animateToTab(targetTab, notifyRadioGroup = true)
                } else {
                    // Tap detection on specific child bounds
                    val tapX = event.x
                    var tappedIndex = selectedIndex
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        if (tapX >= child.left && tapX <= child.right) {
                            tappedIndex = i
                            break
                        }
                    }
                    animateToTab(tappedIndex, notifyRadioGroup = true)
                }

                isDragging = false
                startLiftAnimation(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
                dragOffsetPx = 0f
                startLiftAnimation(false)
                animateToTab(selectedIndex, notifyRadioGroup = false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateToTab(targetIndex: Int, notifyRadioGroup: Boolean = true) {
        if (targetIndex !in 0 until childCount) return

        selectedIndex = targetIndex
        lastHapticTab = targetIndex
        updateChildScales(targetIndex)

        if (notifyRadioGroup) {
            val child = getChildAt(targetIndex)
            if (child != null && child.id != checkedRadioButtonId) {
                isInternalChecking = true
                super.check(child.id)
                isInternalChecking = false
                externalCheckedChangeListener?.onCheckedChanged(this, child.id)
                try {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } catch (_: Exception) {}
            }
        }

        travelAnimator?.cancel()
        val fromPos = currentPosition
        val toPos = targetIndex.toFloat()

        travelAnimator = ValueAnimator.ofFloat(fromPos, toPos).apply {
            duration = 320L
            // Under-damped spring settle matching GlassSpring (damping 0.84, stiffness 220)
            interpolator = OvershootInterpolator(1.35f)
            addUpdateListener { va ->
                val pos = va.animatedValue as Float
                lag = abs(toPos - pos).coerceIn(0f, 1f)
                currentPosition = pos
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lag = 0f
                    currentPosition = toPos
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun startLiftAnimation(toLift: Boolean) {
        liftAnimator?.cancel()
        val target = if (toLift) 1.0f else 0.0f
        liftAnimator = ValueAnimator.ofFloat(lift, target).apply {
            duration = if (toLift) 130L else 240L
            interpolator = if (toLift) DecelerateInterpolator() else OvershootInterpolator(1.6f)
            addUpdateListener { va ->
                lift = va.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun updateChildScales(activeIdx: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val targetScale = if (i == activeIdx) 1.04f else 1.0f
            child.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        travelAnimator?.cancel()
        liftAnimator?.cancel()
    }
}
