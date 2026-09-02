package com.fakegps.mocklocation.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences

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

    fun getCurrentTheme(context: Context): ColorTheme {
        val prefs = AppSettingsPreferences(context)
        val currentId = prefs.appThemeColor
        return THEMES.find { it.id.equals(currentId, ignoreCase = true) } ?: THEMES.first()
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
}
