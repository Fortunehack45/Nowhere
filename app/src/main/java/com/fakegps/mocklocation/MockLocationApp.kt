package com.fakegps.mocklocation

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.preference.PreferenceManager
import com.fakegps.mocklocation.ads.AdManager
import com.fakegps.mocklocation.ads.AppOpenAdManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import org.osmdroid.config.Configuration

class MockLocationApp : Application() {

    private lateinit var appOpenAdManager: AppOpenAdManager

    override fun onCreate() {
        super.onCreate()
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        Configuration.getInstance().load(this@MockLocationApp, sharedPrefs)
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 NowhereLocationSimulator/1.0"
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

        // Network & Mobile Data Protection Guard:
        // Ensure VPN is never forcefully auto-started unless user explicitly opts in via IP Changer / Ghost Shield.
        val networkProtectionV1 = sharedPrefs.getBoolean("key_network_protection_v1", false)
        if (!networkProtectionV1) {
            val sessionPrefs = SessionPreferences(this)
            settingsPrefs.isAutoVpnSyncEnabled = false
            sessionPrefs.isIpMaskingEnabled = false
            sharedPrefs.edit().putBoolean("key_network_protection_v1", true).apply()
            try {
                com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this)
            } catch (ignored: Exception) {}
        }
    }
}
