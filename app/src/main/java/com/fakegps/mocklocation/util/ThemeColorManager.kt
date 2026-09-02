package com.fakegps.mocklocation.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ColorTheme(
    val id: String,
    val displayName: String,
    val emoji: String,
    val primaryColorHex: String,
    val darkColorHex: String,
    val lightTintHex: String,
    val glowColorHex: String
)

object ThemeColorManager {

    val THEMES = listOf(
        ColorTheme("RED", "Crimson Red (Default)", "🔴", "#E41B1B", "#B91C1C", "#FEE2E2", "#20E41B1B"),
        ColorTheme("BLUE", "Electric Blue", "🔵", "#2563EB", "#1D4ED8", "#DBEAFE", "#202563EB"),
        ColorTheme("CYAN", "Cyberpunk Cyan", "💎", "#00B4D8", "#0077B6", "#CFFAFE", "#2000B4D8"),
        ColorTheme("GREEN", "Matrix Emerald", "🟢", "#10B981", "#059669", "#D1FAE5", "#2010B981"),
        ColorTheme("PURPLE", "Royal Purple", "🟣", "#8B5CF6", "#6D28D9", "#EDE9FE", "#208B5CF6"),
        ColorTheme("GOLD", "Sunset Amber", "🟠", "#F59E0B", "#B45309", "#FEF3C7", "#20F59E0B"),
        ColorTheme("PINK", "Neon Rose", "🌸", "#EC4899", "#BE185D", "#FCE7F3", "#20EC4899")
    )

    private val _themeChangeFlow = MutableSharedFlow<ColorTheme>(replay = 1)
    val themeChangeFlow: SharedFlow<ColorTheme> = _themeChangeFlow.asSharedFlow()

    // All known primary palette colors across themes
    private val ALL_PRIMARY_COLORS by lazy {
        THEMES.map { Color.parseColor(it.primaryColorHex) }.toSet() +
        setOf(
            Color.parseColor("#E41B1B"),
            Color.parseColor("#E53935"),
            Color.parseColor("#B91C1C"),
            Color.parseColor("#EF4444"),
            Color.parseColor("#DC2626")
        )
    }

    // All known light tint palette colors across themes
    private val ALL_LIGHT_TINTS by lazy {
        THEMES.map { Color.parseColor(it.lightTintHex) }.toSet() +
        setOf(
            Color.parseColor("#FEE2E2"),
            Color.parseColor("#FFEBEE")
        )
    }

    fun getCurrentTheme(context: Context): ColorTheme {
        val prefs = AppSettingsPreferences(context)
        val currentId = prefs.appThemeColor
        return THEMES.find { it.id.equals(currentId, ignoreCase = true) } ?: THEMES.first()
    }

    fun setAppThemeColor(context: Context, themeId: String) {
        val prefs = AppSettingsPreferences(context)
        prefs.appThemeColor = themeId
        val theme = getCurrentTheme(context)
        _themeChangeFlow.tryEmit(theme)
    }

    fun getPrimaryColor(context: Context): Int {
        return Color.parseColor(getCurrentTheme(context).primaryColorHex)
    }

    fun getLightTintColor(context: Context): Int {
        return Color.parseColor(getCurrentTheme(context).lightTintHex)
    }

    fun getDarkColor(context: Context): Int {
        return Color.parseColor(getCurrentTheme(context).darkColorHex)
    }

    fun getPrimaryColorStateList(context: Context): ColorStateList {
        return ColorStateList.valueOf(getPrimaryColor(context))
    }

    fun getLightTintStateList(context: Context): ColorStateList {
        return ColorStateList.valueOf(getLightTintColor(context))
    }

    fun getGlowColor(context: Context): Int {
        return Color.parseColor(getCurrentTheme(context).glowColorHex)
    }

    fun createCircleDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    fun createSegmentedPillDrawable(primaryColor: Int, cornerRadiusDp: Float = 10f): android.graphics.drawable.StateListDrawable {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val checkedDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(primaryColor)
            setStroke((1f * density).toInt(), primaryColor)
        }
        val uncheckedDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.TRANSPARENT)
        }
        val stateList = android.graphics.drawable.StateListDrawable()
        stateList.addState(intArrayOf(android.R.attr.state_checked), checkedDrawable)
        stateList.addState(intArrayOf(), uncheckedDrawable)
        return stateList
    }

    /**
     * Recursively walks the view hierarchy and transforms ALL red / primary theme
     * elements and light-tint surfaces into the user's chosen theme color palette.
     */
    fun applyThemeRecursively(rootView: View, context: Context) {
        val primaryColor = getPrimaryColor(context)
        val lightTintColor = getLightTintColor(context)
        val primaryCsl = ColorStateList.valueOf(primaryColor)
        val lightTintCsl = ColorStateList.valueOf(lightTintColor)

        applyThemeToViewInternal(rootView, primaryColor, lightTintColor, primaryCsl, lightTintCsl)
    }

    private fun applyThemeToViewInternal(
        view: View,
        primaryColor: Int,
        lightTintColor: Int,
        primaryCsl: ColorStateList,
        lightTintCsl: ColorStateList
    ) {
        when (view) {
            is MaterialButton -> {
                if (view.currentTextColor in ALL_PRIMARY_COLORS) {
                    view.setTextColor(primaryColor)
                }
                if (view.iconTint?.defaultColor in ALL_PRIMARY_COLORS) {
                    view.iconTint = primaryCsl
                }
                if (view.strokeColor?.defaultColor in ALL_PRIMARY_COLORS) {
                    view.strokeColor = primaryCsl
                }
                if (view.backgroundTintList?.defaultColor in ALL_PRIMARY_COLORS) {
                    view.backgroundTintList = primaryCsl
                } else if (view.backgroundTintList?.defaultColor in ALL_LIGHT_TINTS) {
                    view.backgroundTintList = lightTintCsl
                }
            }
            is TextView -> {
                if (view.currentTextColor in ALL_PRIMARY_COLORS) {
                    view.setTextColor(primaryColor)
                } else if (view.currentTextColor in ALL_LIGHT_TINTS) {
                    view.setTextColor(lightTintColor)
                }
            }
            is ImageView -> {
                if (view.imageTintList?.defaultColor in ALL_PRIMARY_COLORS) {
                    view.imageTintList = primaryCsl
                } else if (view.imageTintList?.defaultColor in ALL_LIGHT_TINTS) {
                    view.imageTintList = lightTintCsl
                }
            }
            is MaterialCardView -> {
                if (view.strokeColor in ALL_PRIMARY_COLORS) {
                    view.strokeColor = primaryColor
                }
            }
            is LinearProgressIndicator -> {
                view.setIndicatorColor(primaryColor)
                view.progressTintList = primaryCsl
            }
            is ProgressBar -> {
                view.progressTintList = primaryCsl
            }
            is Slider -> {
                view.trackActiveTintList = primaryCsl
                view.thumbTintList = ColorStateList.valueOf(Color.WHITE)
            }
        }

        // Generic background tint check
        val bgTint = view.backgroundTintList?.defaultColor
        if (bgTint != null) {
            if (bgTint in ALL_PRIMARY_COLORS) {
                view.backgroundTintList = primaryCsl
            } else if (bgTint in ALL_LIGHT_TINTS) {
                view.backgroundTintList = lightTintCsl
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeToViewInternal(view.getChildAt(i), primaryColor, lightTintColor, primaryCsl, lightTintCsl)
            }
        }
    }
}
