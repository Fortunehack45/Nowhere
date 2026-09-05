package com.fakegps.mocklocation.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import androidx.core.widget.ImageViewCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
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

    var isThemeStale: Boolean = false

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

    private val ALL_PRIMARY_HEXES = setOf(
        "#E41B1B", "#E53935", "#B91C1C", "#EF4444", "#DC2626", "#F87171",
        "#2563EB", "#1D4ED8",
        "#00B4D8", "#0077B6",
        "#10B981", "#059669",
        "#8B5CF6", "#6D28D9",
        "#F59E0B", "#B45309",
        "#EC4899", "#BE185D"
    )

    private val ALL_LIGHT_TINT_HEXES = setOf(
        "#FEE2E2", "#FFEBEE", "#3D1010",
        "#DBEAFE", "#CFFAFE", "#D1FAE5", "#EDE9FE", "#FEF3C7", "#FCE7F3"
    )

    fun getCurrentTheme(context: Context): ColorTheme {
        val prefs = AppSettingsPreferences(context)
        val currentId = prefs.appThemeColor
        return THEMES.find { it.id.equals(currentId, ignoreCase = true) } ?: THEMES.first()
    }

    fun setAppThemeColor(context: Context, themeId: String) {
        val prefs = AppSettingsPreferences(context)
        prefs.appThemeColor = themeId
        isThemeStale = true
        val theme = getCurrentTheme(context)
        _themeChangeFlow.tryEmit(theme)
        updateAllAppWidgets(context)
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

    fun isColorMatchingPrimary(color: Int): Boolean {
        if (ALL_PRIMARY_HEXES.any { Color.parseColor(it) == color }) return true
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r > 160 && g < 95 && b < 95)
    }

    fun isColorMatchingLightTint(color: Int): Boolean {
        if (ALL_LIGHT_TINT_HEXES.any { Color.parseColor(it) == color }) return true
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r > 220 && g > 195 && b > 195 && r > g && r > b) || (r in 35..85 && g < 35 && b < 35)
    }

    fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    fun createSegmentedPillDrawable(primaryColor: Int, cornerRadiusDp: Float = 10f): StateListDrawable {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val checkedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(primaryColor)
            setStroke((1f * density).toInt(), primaryColor)
        }
        val uncheckedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.TRANSPARENT)
        }
        val stateList = StateListDrawable()
        stateList.addState(intArrayOf(android.R.attr.state_checked), checkedDrawable)
        stateList.addState(intArrayOf(), uncheckedDrawable)
        return stateList
    }

    /**
     * Dynamically generates the Nowhere Brand Pin Logo with the chosen primary
     * color body, darker quadrant radar shade, and center cutout.
     */
    fun getThemedLogoDrawable(context: Context, primaryColor: Int, darkColor: Int): Drawable {
        val size = (120 * context.resources.displayMetrics.density).toInt().coerceAtLeast(120)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val scale = size / 200f
        val matrix = Matrix().apply { setScale(scale, scale) }

        val path1 = PathParser.createPathFromPathData(
            "M100,20 C133.14,20 160,46.86 160,80 C160,118 100,185 100,185 C100,185 40,118 40,80 C40,46.86 66.86,20 100,20 Z"
        ).apply { transform(matrix) }

        val path2 = PathParser.createPathFromPathData(
            "M100,20 C66.86,20 40,46.86 40,80 L75,80 C75,66.2 86.2,55 100,55 Z"
        ).apply { transform(matrix) }

        val path3 = PathParser.createPathFromPathData(
            "M100,55 C113.8,55 125,66.2 125,80 C125,93.8 113.8,105 100,105 C86.2,105 75,93.8 75,80 C75,66.2 86.2,55 100,55 Z"
        ).apply { transform(matrix) }

        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = primaryColor
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = darkColor
        }
        val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0E1117")
        }

        canvas.drawPath(path1, p1)
        canvas.drawPath(path2, p2)
        canvas.drawPath(path3, p3)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Dynamically generates the map target pin marker as the Nowhere App Logo,
     * scaled ~30% smaller (32dp) with primary theme body, dark quadrant, and high-contrast outline.
     */
    fun getThemedTargetPinDrawable(context: Context, primaryColor: Int, darkColor: Int = primaryColor): Drawable {
        val size = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val scale = size / 24f
        val matrix = Matrix().apply { setScale(scale, scale) }

        val path1 = PathParser.createPathFromPathData(
            "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7z"
        ).apply { transform(matrix) }

        val path2 = PathParser.createPathFromPathData(
            "M12,2A7,7 0 0,0 5,9C5,10.6 5.6,12.3 6.7,14L12,9Z"
        ).apply { transform(matrix) }

        val path3 = PathParser.createPathFromPathData(
            "M12,6.5A2.5,2.5 0 1,0 14.5,9A2.5,2.5 0 0,0 12,6.5Z"
        ).apply { transform(matrix) }

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * scale
            color = Color.WHITE
        }
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = primaryColor
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = darkColor
        }
        val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0E1117")
        }

        canvas.drawPath(path1, shadowPaint)
        canvas.drawPath(path1, p1)
        canvas.drawPath(path2, p2)
        canvas.drawPath(path3, p3)

        return BitmapDrawable(context.resources, bitmap)
    }

    fun createSelectedPlanCardDrawable(primaryColor: Int, context: Context): Drawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(ContextCompat.getColor(context, R.color.surface_card_elevated))
            setStroke((1.5f * density).toInt(), primaryColor)
        }
    }

    fun createDiscountBadgeDrawable(lightTintColor: Int, context: Context): Drawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(lightTintColor)
        }
    }

    /**
     * Recursively walks the view hierarchy and transforms ALL red / primary theme
     * elements, switches, icons, text, and surfaces into the chosen theme color palette.
     */
    fun applyThemeRecursively(rootView: View, context: Context) {
        val primaryColor = getPrimaryColor(context)
        val lightTintColor = getLightTintColor(context)
        val darkColor = getDarkColor(context)
        val primaryCsl = ColorStateList.valueOf(primaryColor)
        val lightTintCsl = ColorStateList.valueOf(lightTintColor)

        applyThemeToViewInternal(rootView, context, primaryColor, lightTintColor, darkColor, primaryCsl, lightTintCsl)
    }

    private fun applyThemeToViewInternal(
        view: View,
        context: Context,
        primaryColor: Int,
        lightTintColor: Int,
        darkColor: Int,
        primaryCsl: ColorStateList,
        lightTintCsl: ColorStateList
    ) {
        when (view) {
            is MaterialSwitch -> {
                val thumbStateList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ),
                    intArrayOf(
                        Color.WHITE,
                        Color.parseColor("#94A3B8")
                    )
                )
                val trackStateList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ),
                    intArrayOf(
                        primaryColor,
                        Color.parseColor("#334155")
                    )
                )
                view.thumbTintList = thumbStateList
                view.trackTintList = trackStateList
            }
            is SwitchCompat -> {
                view.thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#94A3B8"))
                )
                view.trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(primaryColor, Color.parseColor("#334155"))
                )
            }
            is android.widget.RadioGroup -> {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child is android.widget.RadioButton) {
                        child.background = createSegmentedPillDrawable(primaryColor)
                    }
                }
            }
            is android.widget.RadioButton -> {
                view.background = createSegmentedPillDrawable(primaryColor)
            }
            is CompoundButton -> {
                view.buttonTintList = primaryCsl
            }
            is MaterialButton -> {
                val textColor = view.currentTextColor
                if (isColorMatchingPrimary(textColor)) {
                    view.setTextColor(primaryColor)
                } else if (isColorMatchingLightTint(textColor)) {
                    view.setTextColor(darkColor)
                }

                view.iconTint?.defaultColor?.let { iconColor ->
                    if (isColorMatchingPrimary(iconColor)) {
                        view.iconTint = primaryCsl
                    } else if (isColorMatchingLightTint(iconColor)) {
                        view.iconTint = ColorStateList.valueOf(darkColor)
                    }
                }

                view.strokeColor?.defaultColor?.let { stroke ->
                    if (isColorMatchingPrimary(stroke)) {
                        view.strokeColor = primaryCsl
                    }
                }

                view.backgroundTintList?.defaultColor?.let { bg ->
                    if (isColorMatchingPrimary(bg)) {
                        view.backgroundTintList = primaryCsl
                    } else if (isColorMatchingLightTint(bg)) {
                        view.backgroundTintList = lightTintCsl
                    }
                }
            }
            is TextView -> {
                val textColor = view.currentTextColor
                if (isColorMatchingPrimary(textColor)) {
                    view.setTextColor(primaryColor)
                } else if (isColorMatchingLightTint(textColor)) {
                    view.setTextColor(lightTintColor)
                }
            }
            is ImageView -> {
                if (view.id == R.id.ivTopBrandLogo || view.id == R.id.ivSettingsFooterLogo || view.id == R.id.ivWidgetGalleryLogo) {
                    view.setImageDrawable(getThemedLogoDrawable(context, primaryColor, darkColor))
                } else if (view.id == R.id.ivWidgetTeleportBg || view.id == R.id.ivWidgetRoutePlayPauseBg || view.id == R.id.ivWidgetGameBoostToggleBg || view.id == R.id.ivWidgetVpnToggleBg || view.id == R.id.ivWidgetWeatherDetailsBg || view.id == R.id.ivSearchWidgetTeleportBg) {
                    view.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN)
                } else {
                    val tint = ImageViewCompat.getImageTintList(view)?.defaultColor
                    if (tint != null) {
                        if (isColorMatchingPrimary(tint)) {
                            ImageViewCompat.setImageTintList(view, primaryCsl)
                        } else if (isColorMatchingLightTint(tint)) {
                            ImageViewCompat.setImageTintList(view, lightTintCsl)
                        }
                    } else if (view.colorFilter != null) {
                        view.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN)
                    }
                }
            }
            is MaterialCardView -> {
                view.strokeColorStateList?.defaultColor?.let { stroke ->
                    if (isColorMatchingPrimary(stroke)) {
                        view.strokeColor = primaryColor
                    }
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
                applyThemeToSlider(view, primaryColor, context)
            }
        }

        // Generic background tint check
        val bgTint = view.backgroundTintList?.defaultColor
        if (bgTint != null) {
            if (isColorMatchingPrimary(bgTint)) {
                view.backgroundTintList = primaryCsl
            } else if (isColorMatchingLightTint(bgTint)) {
                view.backgroundTintList = lightTintCsl
            }
        }

        // Generic background drawable check
        val bgDrawable = view.background
        if (bgDrawable is GradientDrawable) {
            bgDrawable.color?.defaultColor?.let { color ->
                if (isColorMatchingPrimary(color)) {
                    bgDrawable.setColor(primaryColor)
                } else if (isColorMatchingLightTint(color)) {
                    bgDrawable.setColor(lightTintColor)
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeToViewInternal(
                    view.getChildAt(i),
                    context,
                    primaryColor,
                    lightTintColor,
                    darkColor,
                    primaryCsl,
                    lightTintCsl
                )
            }
        }
    }

    fun applyThemeToSlider(slider: Slider, primaryColor: Int, context: Context) {
        val primaryCsl = ColorStateList.valueOf(primaryColor)
        slider.trackActiveTintList = primaryCsl
        slider.trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#334155"))
        slider.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        slider.haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        slider.tickActiveTintList = ColorStateList.valueOf(Color.WHITE)
        slider.tickInactiveTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))

        fun tintTooltipDrawables() {
            try {
                var currentClass: Class<*>? = slider.javaClass
                var labelsField: java.lang.reflect.Field? = null
                while (currentClass != null && labelsField == null) {
                    try {
                        labelsField = currentClass.getDeclaredField("labels")
                    } catch (e: NoSuchFieldException) {
                        currentClass = currentClass.superclass
                    }
                }
                labelsField?.isAccessible = true
                val labelsList = labelsField?.get(slider) as? List<*>
                labelsList?.forEach { label ->
                    if (label is com.google.android.material.shape.MaterialShapeDrawable) {
                        label.fillColor = primaryCsl
                    }
                    try {
                        val setAppearanceMethod = label?.javaClass?.getMethod("setTextAppearanceResource", Int::class.javaPrimitiveType)
                        setAppearanceMethod?.invoke(label, R.style.TextAppearance_Nowhere_SliderTooltip)
                    } catch (ignored: Exception) {}
                }
            } catch (ignored: Exception) {}
        }

        tintTooltipDrawables()
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                tintTooltipDrawables()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                tintTooltipDrawables()
            }
        })
    }

    /**
     * Determines whether widgets should render in Dark mode or Light mode based on the user's
     * App Theme setting in Nowhere ("DARK", "LIGHT", or "SYSTEM").
     */
    fun isWidgetDarkMode(context: Context): Boolean {
        val prefs = AppSettingsPreferences(context)
        return when (prefs.appTheme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> {
                val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun getWidgetGlassBackgroundRes(context: Context): Int {
        return if (isWidgetDarkMode(context)) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
    }

    fun getWidgetButtonBackgroundRes(context: Context): Int {
        return if (isWidgetDarkMode(context)) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
    }

    fun getWidgetPrimaryTextColor(context: Context): Int {
        return if (isWidgetDarkMode(context)) Color.WHITE else Color.BLACK
    }

    fun getWidgetSecondaryTextColor(context: Context): Int {
        return if (isWidgetDarkMode(context)) Color.parseColor("#AEAEB2") else Color.parseColor("#636366")
    }

    fun updateAllAppWidgets(context: Context) {
        try {
            com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.updateAllWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereGameBoostWidgetProvider.updateAllGameBoostWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereSessionTimerWidgetProvider.updateAllSessionWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider.updateAllVpnWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereWeatherWidgetProvider.updateAllWeatherWidgets(context)
            com.fakegps.mocklocation.ui.widget.NowhereSearchWidgetProvider.updateAllSearchWidgets(context)
            com.fakegps.mocklocation.automation.widget.NowhereAutomationWidgetProvider.updateAllAutomationWidgets(context)
        } catch (ignored: Exception) {}
    }
}
