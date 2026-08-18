package com.fakegps.mocklocation

import android.app.Application
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.ads.AdManager
import com.fakegps.mocklocation.ads.AppOpenAdManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    private lateinit var appOpenAdManager: AppOpenAdManager

    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration with ultra-fast multi-threaded downloading & caching
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().apply {
            load(this@MockLocationApp, sharedPrefs)
            userAgentValue = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 NowhereLocationSimulator/1.0"
            cacheMapTileOvershoot = 8
            cacheMapTileCount = 120.toShort()
            tileDownloadThreads = 12.toShort()
            tileDownloadMaxQueueSize = 80.toShort()
            tileFileSystemThreads = 8.toShort()
            tileFileSystemCacheMaxBytes = 600L * 1024L * 1024L
            tileFileSystemCacheTrimBytes = 500L * 1024L * 1024L
            isMapViewHardwareAccelerated = true
        }

        // Register custom satellite hybrid tile source with OSMDroid factory
        org.osmdroid.tileprovider.tilesource.TileSourceFactory.addTileSource(com.fakegps.mocklocation.data.preferences.SATELLITE_TILE_SOURCE)

        // Apply user selected theme on startup
        val settingsPrefs = AppSettingsPreferences(this)
        settingsPrefs.applyTheme()

        // Initialize Google Mobile Ads SDK (AdMob)
        AdManager.initialize(this)

        // Initialize Google AdMob App Open Ads Manager
        appOpenAdManager = AppOpenAdManager(this)
    }
}
