package com.fakegps.mocklocation

import android.app.Application
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration with ultra-fast multi-threaded downloading & caching
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().apply {
            load(this@MockLocationApp, sharedPrefs)
            userAgentValue = "NowhereLocationSimulator/1.0 (Android; FastTileDownloader)"
            cacheMapTileOvershoot = 8
            cacheMapTileCount = 120.toShort()
            tileDownloadThreads = 12.toShort()
            tileDownloadMaxQueueSize = 80.toShort()
            tileFileSystemThreads = 8.toShort()
            tileFileSystemCacheMaxBytes = 600L * 1024L * 1024L
            tileFileSystemCacheTrimBytes = 500L * 1024L * 1024L
            isMapViewHardwareAccelerated = true
        }

        // Apply user selected theme on startup
        val settingsPrefs = AppSettingsPreferences(this)
        settingsPrefs.applyTheme()
    }
}
