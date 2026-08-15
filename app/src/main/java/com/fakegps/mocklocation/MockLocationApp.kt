package com.fakegps.mocklocation

import android.app.Application
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().load(this, sharedPrefs)
        Configuration.getInstance().userAgentValue = packageName

        // Apply user selected theme on startup
        val settingsPrefs = AppSettingsPreferences(this)
        settingsPrefs.applyTheme()
    }
}
