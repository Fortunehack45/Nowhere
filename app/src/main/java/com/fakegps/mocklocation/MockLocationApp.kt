package com.fakegps.mocklocation

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.ads.AdManager
import com.fakegps.mocklocation.ads.AppOpenAdManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    private lateinit var appOpenAdManager: AppOpenAdManager

    override fun onCreate() {
        super.onCreate()
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = activityManager?.isLowRamDevice == true || (Runtime.getRuntime().maxMemory() / (1024 * 1024)) < 192

        Configuration.getInstance().apply {
            load(this@MockLocationApp, sharedPrefs)
            userAgentValue = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 NowhereLocationSimulator/1.0"
            if (isLowRam) {
                cacheMapTileOvershoot = 2
                cacheMapTileCount = 24.toShort()
                tileDownloadThreads = 3.toShort()
                tileDownloadMaxQueueSize = 20.toShort()
                tileFileSystemThreads = 2.toShort()
                tileFileSystemCacheMaxBytes = 50L * 1024L * 1024L
                tileFileSystemCacheTrimBytes = 40L * 1024L * 1024L
            } else {
                cacheMapTileOvershoot = 4
                cacheMapTileCount = 60.toShort()
                tileDownloadThreads = 8.toShort()
                tileDownloadMaxQueueSize = 40.toShort()
                tileFileSystemThreads = 4.toShort()
                tileFileSystemCacheMaxBytes = 200L * 1024L * 1024L
                tileFileSystemCacheTrimBytes = 160L * 1024L * 1024L
            }
            isMapViewHardwareAccelerated = true
        }

        // Register custom satellite hybrid tile source with OSMDroid factory
        org.osmdroid.tileprovider.tilesource.TileSourceFactory.addTileSource(com.fakegps.mocklocation.data.preferences.SATELLITE_TILE_SOURCE)

        // Apply user selected theme on startup
        val settingsPrefs = AppSettingsPreferences(this)
        settingsPrefs.applyTheme()

        // Initialize Google Play Billing Manager (Subscriptions & Entitlements)
        com.fakegps.mocklocation.billing.BillingManager.getInstance(this)

        // Initialize Google Mobile Ads SDK (AdMob)
        AdManager.initialize(this)

        // Initialize Google AdMob App Open Ads Manager
        appOpenAdManager = AppOpenAdManager(this)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            System.gc()
        } catch (ignored: Exception) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            try {
                System.gc()
            } catch (ignored: Exception) {}
        }
    }
}
