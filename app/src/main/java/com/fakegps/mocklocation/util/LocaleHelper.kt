package com.fakegps.mocklocation.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import java.util.Locale

data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val flagEmoji: String
)

object LocaleHelper {

    const val KEY_APP_LANGUAGE = "key_app_selected_language"
    const val DEFAULT_LANGUAGE = "en"

    val TOP_10_LANGUAGES = listOf(
        SupportedLanguage("en", "English", "English", "🇺🇸"),
        SupportedLanguage("es", "Español", "Spanish", "🇪🇸"),
        SupportedLanguage("zh", "简体中文", "Chinese (Simplified)", "🇨🇳"),
        SupportedLanguage("hi", "हिन्दी", "Hindi", "🇮🇳"),
        SupportedLanguage("ar", "العربية", "Arabic", "🇸🇦"),
        SupportedLanguage("fr", "Français", "French", "🇫🇷"),
        SupportedLanguage("pt", "Português", "Portuguese", "🇧🇷"),
        SupportedLanguage("ru", "Русский", "Russian", "🇷🇺"),
        SupportedLanguage("de", "Deutsch", "German", "🇩🇪"),
        SupportedLanguage("ja", "日本語", "Japanese", "🇯🇵")
    )

    fun getSelectedLanguage(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_APP_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun getSelectedLanguageItem(context: Context): SupportedLanguage {
        val code = getSelectedLanguage(context)
        return TOP_10_LANGUAGES.find { it.code == code } ?: TOP_10_LANGUAGES.first()
    }

    fun applyLanguage(context: Context, languageCode: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(KEY_APP_LANGUAGE, languageCode).apply()

        // Modern AndroidX in-app language switching (supported from Android 13 down to API 21)
        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)

        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
    }

    fun wrapContext(context: Context): Context {
        val languageCode = getSelectedLanguage(context)
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }
}
