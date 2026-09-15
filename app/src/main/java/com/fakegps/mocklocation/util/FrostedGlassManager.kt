package com.fakegps.mocklocation.util

import android.app.Dialog
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Utility for hardware-accelerated GPU frosted glass blur and window backdrop effects.
 * Provides native WindowManager.FLAG_BLUR_BEHIND on Android 12+ (API 31+) with graceful
 * multi-layer specular glassmorphism fallback on older platforms.
 */
object FrostedGlassManager {

    /**
     * Applies hardware-accelerated GPU window blur behind dialogs, bottom sheets, and floating windows.
     */
    fun applyWindowBlur(dialog: Dialog?, radiusDp: Int = 25) {
        val window = dialog?.window ?: return
        applyWindowBlur(window, radiusDp)
    }

    /**
     * Configures a Window to blur the underlying activity/map background on Android 12+.
     */
    fun applyWindowBlur(window: Window, radiusDp: Int = 25) {
        try {
            // Set light dim amount (0.15f) instead of default heavy 0.60f dark blanket,
            // allowing the underlying blurred map colors and content to shine through the frosted glass!
            window.setDimAmount(0.15f)
            window.setBackgroundDrawableResource(android.R.color.transparent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val density = window.context.resources.displayMetrics.density
                val blurPx = (radiusDp * density).toInt()

                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = blurPx
                window.attributes = params
            }
        } catch (ignored: Exception) {
            // Non-fatal if device vendor restricted window blur in battery-saver mode
        }
    }

    /**
     * Applies RenderEffect blur to a target backdrop container on Android 12+.
     */
    fun applyViewBlur(view: View?, radiusDp: Float = 25f) {
        if (view == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val density = view.resources.displayMetrics.density
                val blurPx = radiusDp * density
                val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                view.setRenderEffect(effect)
            }
        } catch (ignored: Exception) {
            // Non-fatal on unsupported GPUs
        }
    }

    /**
     * Clears any RenderEffect on a view.
     */
    fun clearViewBlur(view: View?) {
        if (view == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(null)
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Applies adjustable frosted glass blur styling to the Main Page HUD menus:
     * top brand bar, search bar, side tools deck, and bottom navigation container.
     */
    fun applyHudFrostedGlass(
        context: android.content.Context,
        cards: List<com.google.android.material.card.MaterialCardView>,
        edgeBlurViews: List<View>,
        blurPercent: Int = 90
    ) {
        val clampedPercent = blurPercent.coerceIn(50, 100)
        val alphaFraction = clampedPercent / 100f
        val alphaInt = (alphaFraction * 255f).toInt().coerceIn(0, 255)

        val isNight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val cardBgColor = if (isNight) {
            // Apple Dark Ash: blend #1C1C1E to #2C2C2E based on alpha
            android.graphics.Color.argb(alphaInt, 36, 36, 40)
        } else {
            // Apple Frosted Light: blend white with alpha
            android.graphics.Color.argb(alphaInt, 255, 255, 255)
        }

        val strokeColor = if (isNight) {
            // Specular hairline border
            android.graphics.Color.argb(
                (alphaInt * 0.45f).toInt().coerceIn(40, 120),
                255, 255, 255
            )
        } else {
            android.graphics.Color.argb(
                (alphaInt * 0.20f).toInt().coerceIn(25, 60),
                0, 0, 0
            )
        }

        cards.forEach { card ->
            card.setCardBackgroundColor(cardBgColor)
            card.strokeColor = strokeColor
            card.strokeWidth = (1.2f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }

        edgeBlurViews.forEach { view ->
            view.alpha = (alphaFraction * 1.05f).coerceIn(0.4f, 1.0f)
        }
    }
}
