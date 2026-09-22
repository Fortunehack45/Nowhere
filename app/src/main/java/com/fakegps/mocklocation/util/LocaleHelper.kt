package com.fakegps.mocklocation.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.asSharedFlow
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

    var isLanguageStale: Boolean = false

    private val _languageChangeFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1)
    val languageChangeFlow: kotlinx.coroutines.flow.SharedFlow<String> = _languageChangeFlow.asSharedFlow()

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

        isLanguageStale = true

        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)

        // Modern AndroidX in-app language switching (supported from Android 13 down to API 21)
        try {
            val appLocale = LocaleListCompat.forLanguageTags(languageCode)
            AppCompatDelegate.setApplicationLocales(appLocale)
        } catch (e: Exception) {
            android.util.Log.w("LocaleHelper", "Failed to setApplicationLocales", e)
        }

        // Force synchronous update across active Context and ApplicationContext resources
        updateResources(context, locale)
        try {
            val appContext = context.applicationContext
            if (appContext != null && appContext != context) {
                updateResources(appContext, locale)
            }
        } catch (ignored: Exception) {}

        _languageChangeFlow.tryEmit(languageCode)
    }

    fun updateResources(context: Context, locale: Locale) {
        try {
            val res = context.resources
            val config = Configuration(res.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
        } catch (e: Exception) {
            android.util.Log.w("LocaleHelper", "Error updating resources", e)
        }
    }

    fun wrapContext(context: Context): Context {
        val languageCode = getSelectedLanguage(context)
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLayoutDirection(locale)
            }
            val newContext = context.createConfigurationContext(config)
            @Suppress("DEPRECATION")
            newContext.resources.updateConfiguration(config, newContext.resources.displayMetrics)
            return newContext
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context
        }
    }

    fun getThemedLocalizedContext(context: Context): Context {
        val wrapped = wrapContext(context)
        return androidx.appcompat.view.ContextThemeWrapper(wrapped, com.fakegps.mocklocation.R.style.Theme_MockLocation)
    }
}
