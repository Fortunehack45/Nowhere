package com.fakegps.mocklocation

import android.app.Application
import android.content.ComponentCallbacks2
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

        Configuration.getInstance().load(this@MockLocationApp, sharedPrefs)
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 NowhereLocationSimulator/1.0"
        com.fakegps.mocklocation.util.RamOptimizationManager.configureOptimalMemoryLimits(this)

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
        com.fakegps.mocklocation.util.RamOptimizationManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, null)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.fakegps.mocklocation.util.RamOptimizationManager.trimMemory(level, null)
    }
}
