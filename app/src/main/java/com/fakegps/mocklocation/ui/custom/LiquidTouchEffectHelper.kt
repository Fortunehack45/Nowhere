package com.fakegps.mocklocation.ui.custom

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Reusable Liquid Touch & Drag Physics Engine inspired by modern iOS/Vibra_Music liquid glass interactions.
 *
 * Provides:
 * - Elastic squish on touch down (scale down to ~0.94x)
 * - Rubber-band translation following touch with directional liquid squash & stretch deformation
 * - Expansive liquid spring bounce on release (overshoot to ~1.09x before settling to 1.0x)
 * - Tactile haptic feedback on boundary ticks and clicks
 * - Support for standard clicks and long-clicks
 */
object LiquidTouchEffectHelper {

    private const val PRESS_SCALE = 0.94f
    private const val EXPAND_SCALE = 1.09f
    private const val DRAG_RESISTANCE = 0.28f
    private const val MAX_DRAG_DP = 20f
    private const val CLICK_SLOP_DP = 16f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        view: View,
        pressScale: Float = PRESS_SCALE,
        expandScale: Float = EXPAND_SCALE,
        dragResistance: Float = DRAG_RESISTANCE,
        enableDragStretch: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        val density = Resources.getSystem().displayMetrics.density
        val maxDragPx = MAX_DRAG_DP * density
        val clickSlopPx = CLICK_SLOP_DP * density
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        var downX = 0f
        var downY = 0f
        var isLongClickTriggered = false
        var activeAnimator: AnimatorSet? = null

        val longPressRunnable = Runnable {
            if (view.isLongClickable && view.performLongClick()) {
                isLongClickTriggered = true
            }
        }

        view.isClickable = true
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isLongClickTriggered = false

                    activeAnimator?.cancel()
                    v.removeCallbacks(longPressRunnable)
                    v.postDelayed(longPressRunnable, longPressTimeout)

                    // Quick elastic squish on press
                    val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, pressScale)
                    val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, pressScale)
                    activeAnimator = AnimatorSet().apply {
                        playTogether(sx, sy)
                        duration = 110
                        interpolator = DecelerateInterpolator()
                        start()
                    }

                    try {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } catch (_: Exception) {}
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (distance > clickSlopPx) {
                        v.removeCallbacks(longPressRunnable)
                    }

                    if (enableDragStretch) {
                        activeAnimator?.cancel()
                        activeAnimator = null

                        // Rubber-band elastic drag displacement
                        val tx = (dx * dragResistance).coerceIn(-maxDragPx, maxDragPx)
                        val ty = (dy * dragResistance).coerceIn(-maxDragPx, maxDragPx)
                        v.translationX = tx
                        v.translationY = ty

                        // Liquid directional squash & stretch:
                        // Elongate along drag axis, compress perpendicular axis (volume conservation)
                        val stretch = (distance / (maxDragPx * 2.5f)).coerceIn(0f, 0.10f)
                        val absDx = abs(dx)
                        val absDy = abs(dy)
                        val totalDelta = (absDx + absDy).coerceAtLeast(1f)
                        val hWeight = absDx / totalDelta
                        val vWeight = absDy / totalDelta

                        v.scaleX = pressScale + (stretch * hWeight) - (stretch * 0.4f * vWeight)
                        v.scaleY = pressScale + (stretch * vWeight) - (stretch * 0.4f * hWeight)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val isClick = distance <= clickSlopPx

                    activeAnimator?.cancel()

                    // Expansive liquid spring bounce
                    val animTx = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f).apply {
                        duration = 260
                        interpolator = DecelerateInterpolator(1.8f)
                    }
                    val animTy = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f).apply {
                        duration = 260
                        interpolator = DecelerateInterpolator(1.8f)
                    }

                    // Two-phase spring: expand past 1.0 (liquid splash), then settle to 1.0
                    val scaleAnim = ValueAnimator.ofFloat(v.scaleX, expandScale, 1.0f).apply {
                        duration = 320
                        interpolator = OvershootInterpolator(2.4f)
                        addUpdateListener { va ->
                            val s = va.animatedValue as Float
                            v.scaleX = s
                            v.scaleY = s
                        }
                    }

                    activeAnimator = AnimatorSet().apply {
                        playTogether(animTx, animTy, scaleAnim)
                        start()
                    }

                    if (isClick && !isLongClickTriggered) {
                        try {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (_: Exception) {}

                        if (onClick != null) {
                            onClick()
                        } else {
                            v.performClick()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    activeAnimator?.cancel()
                    v.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    true
                }

                else -> false
            }
        }
    }
}

/**
 * Extension helper to attach liquid touch & drag effect to any View cleanly.
 */
fun View.applyLiquidTouch(
    dragResistance: Float = 0.28f,
    enableDragStretch: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    LiquidTouchEffectHelper.attach(
        view = this,
        dragResistance = dragResistance,
        enableDragStretch = enableDragStretch,
        onClick = onClick
    )
}
