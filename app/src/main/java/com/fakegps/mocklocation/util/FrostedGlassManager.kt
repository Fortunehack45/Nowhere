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
    fun applyWindowBlur(dialog: Dialog?, radiusDp: Int = 35) {
        val window = dialog?.window ?: return
        applyWindowBlur(window, radiusDp)
    }

    /**
     * Configures a Window to blur the underlying activity/map background on Android 12+.
     */
    fun applyWindowBlur(window: Window, radiusDp: Int = 35, dimAmount: Float = 0.18f) {
        try {
            // Set light dim amount (0.18f) instead of heavy 0.60f dark blanket,
            // allowing the underlying blurred map colors and content to shine through the frosted glass!
            window.setDimAmount(dimAmount)
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
     * Applies hardware-accelerated GPU window blur to a BottomSheetDialogFragment
     * and clears the default opaque container background.
     */
    fun applyBottomSheetBlur(sheet: com.google.android.material.bottomsheet.BottomSheetDialogFragment, radiusDp: Int = 35) {
        val dlg = sheet.dialog ?: return
        applyWindowBlur(dlg, radiusDp)
        try {
            dlg.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)
        } catch (ignored: Exception) {}
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

        val isNight = ThemeColorManager.isDarkMode(context)

        // Apple Translucent Acrylic: 85-92% opacity gives rich depth while letting map texture bleed through
        val cardBgColor = if (isNight) {
            val darkAlpha = (alphaInt * 0.90f).toInt().coerceIn(160, 240)
            android.graphics.Color.argb(darkAlpha, 26, 26, 32)
        } else {
            val lightAlpha = (alphaInt * 0.88f).toInt().coerceIn(170, 245)
            android.graphics.Color.argb(lightAlpha, 255, 255, 255)
        }

        // Clean subtle border without any glowing effect
        val strokeColor = if (isNight) {
            android.graphics.Color.parseColor("#38383A")
        } else {
            android.graphics.Color.parseColor("#E5E5EA")
        }

        cards.forEach { card ->
            card.setCardBackgroundColor(cardBgColor)
            card.strokeColor = strokeColor
            card.strokeWidth = (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }

        edgeBlurViews.forEach { view ->
            view.alpha = (alphaFraction * 1.05f).coerceIn(0.4f, 1.0f)
        }

        // On Android 12+ (API 31+), apply hardware-accelerated RenderEffect blur directly onto the edge overlays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = (alphaFraction * 32f).coerceIn(12f, 40f)
            val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            edgeBlurViews.forEach { view ->
                try {
                    view.setRenderEffect(effect)
                } catch (ignored: Exception) {}
            }
        }
    }
}
