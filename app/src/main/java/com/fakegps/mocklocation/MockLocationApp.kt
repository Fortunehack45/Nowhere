package com.fakegps.mocklocation

import android.app.Application
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration with generous offline & disk caching
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().load(this, sharedPrefs)
        Configuration.getInstance().userAgentValue = "NowhereLocationSimulator/1.0 (Android)"
        Configuration.getInstance().cacheMapTileOvershoot = 4
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 250L * 1024 * 1024

        // Apply user selected theme on startup
        val settingsPrefs = AppSettingsPreferences(this)
        settingsPrefs.applyTheme()
    }
}
