package com.fakegps.mocklocation.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

class AppSettingsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nowhere_app_settings_prefs", Context.MODE_PRIVATE)

    companion object {
        // Advanced Location Simulation Keys
        const val KEY_USE_FUSED_PROVIDER = "key_use_fused_provider"
        const val KEY_RANDOMIZE_JITTER = "key_randomize_jitter"
        const val KEY_JITTER_RADIUS_METERS = "key_jitter_radius_meters"
        const val KEY_TRUNCATE_DECIMALS = "key_truncate_decimals" // -1 = off/full, 2, 4, 6
        const val KEY_DEFAULT_ALTITUDE = "key_default_altitude"
        const val KEY_RANDOMIZE_ALTITUDE = "key_randomize_altitude"
        const val KEY_BASE_ACCURACY = "key_base_accuracy"
        const val KEY_UPDATE_INTERVAL_MOVING = "key_update_interval_moving"
        const val KEY_UPDATE_INTERVAL_STATIONARY = "key_update_interval_stationary"

        // Map, Display & Units Keys
        const val KEY_APP_THEME = "key_app_theme" // "DARK", "LIGHT", "SYSTEM"
        const val KEY_MAP_TILE_SOURCE = "key_map_tile_source" // "MAPNIK", "TOPO", "WIKIMEDIA", "USGS_SAT"
        const val KEY_DISTANCE_UNIT = "key_distance_unit" // "METRIC" (km/h, m), "IMPERIAL" (mph, ft)
        const val KEY_MAP_ANIMATIONS = "key_map_animations"
        const val KEY_HAPTIC_FEEDBACK = "key_haptic_feedback"
        const val KEY_NOTIFICATION_DETAILS = "key_notification_details"
    }

    // --- Advanced Engine Settings ---

    var useFusedProvider: Boolean
        get() = prefs.getBoolean(KEY_USE_FUSED_PROVIDER, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FUSED_PROVIDER, value).apply()

    var randomizeJitter: Boolean
        get() = prefs.getBoolean(KEY_RANDOMIZE_JITTER, false)
        set(value) = prefs.edit().putBoolean(KEY_RANDOMIZE_JITTER, value).apply()

    var jitterRadiusMeters: Float
        get() = prefs.getFloat(KEY_JITTER_RADIUS_METERS, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_JITTER_RADIUS_METERS, value).apply()

    var truncateDecimals: Int
        get() = prefs.getInt(KEY_TRUNCATE_DECIMALS, -1) // -1 is full precision
        set(value) = prefs.edit().putInt(KEY_TRUNCATE_DECIMALS, value).apply()

    var defaultAltitude: Float
        get() = prefs.getFloat(KEY_DEFAULT_ALTITUDE, 15.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_ALTITUDE, value).apply()

    var randomizeAltitude: Boolean
        get() = prefs.getBoolean(KEY_RANDOMIZE_ALTITUDE, false)
        set(value) = prefs.edit().putBoolean(KEY_RANDOMIZE_ALTITUDE, value).apply()

    var baseAccuracy: Float
        get() = prefs.getFloat(KEY_BASE_ACCURACY, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_BASE_ACCURACY, value).apply()

    var updateIntervalMovingMs: Long
        get() = prefs.getLong(KEY_UPDATE_INTERVAL_MOVING, 1000L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_INTERVAL_MOVING, value).apply()

    var updateIntervalStationaryMs: Long
        get() = prefs.getLong(KEY_UPDATE_INTERVAL_STATIONARY, 1000L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_INTERVAL_STATIONARY, value).apply()

    // --- General, Theme, Map & Units ---

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, "DARK") ?: "DARK"
        set(value) {
            prefs.edit().putString(KEY_APP_THEME, value).apply()
            applyTheme(value)
        }

    var mapTileSource: String
        get() = prefs.getString(KEY_MAP_TILE_SOURCE, "MAPNIK") ?: "MAPNIK"
        set(value) = prefs.edit().putString(KEY_MAP_TILE_SOURCE, value).apply()

    var distanceUnit: String
        get() = prefs.getString(KEY_DISTANCE_UNIT, "METRIC") ?: "METRIC"
        set(value) = prefs.edit().putString(KEY_DISTANCE_UNIT, value).apply()

    var enableMapAnimations: Boolean
        get() = prefs.getBoolean(KEY_MAP_ANIMATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_MAP_ANIMATIONS, value).apply()

    var enableHapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    var enableDetailedNotifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_DETAILS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_DETAILS, value).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("key_has_completed_onboarding", false)
        set(value) = prefs.edit().putBoolean("key_has_completed_onboarding", value).apply()

    // --- 24-Hour Rewarded Ad-Free Pass ---

    var watchedRewardAdsCount: Int
        get() = prefs.getInt("key_watched_reward_ads_count", 0)
        set(value) = prefs.edit().putInt("key_watched_reward_ads_count", value).apply()

    var adFreeUntilTimestamp: Long
        get() = prefs.getLong("key_ad_free_until_timestamp", 0L)
        set(value) = prefs.edit().putLong("key_ad_free_until_timestamp", value).apply()

    val isAdFreeActive: Boolean
        get() = System.currentTimeMillis() < adFreeUntilTimestamp

    /**
     * Records a watched rewarded ad. If 5 ads are watched, activates 24 hours of ad-free access!
     * Returns a pair of (newWatchedCount, didUnlock24hPass)
     */
    fun recordRewardedAdWatched(): Pair<Int, Boolean> {
        val current = watchedRewardAdsCount + 1
        return if (current >= 5) {
            val oneDayMillis = 24 * 60 * 60 * 1000L
            val currentFreeUntil = if (isAdFreeActive) adFreeUntilTimestamp else System.currentTimeMillis()
            adFreeUntilTimestamp = currentFreeUntil + oneDayMillis
            watchedRewardAdsCount = 0
            Pair(5, true)
        } else {
            watchedRewardAdsCount = current
            Pair(current, false)
        }
    }

    fun getAdFreeRemainingTimeText(): String {
        if (!isAdFreeActive) return "Inactive"
        val remainingMillis = adFreeUntilTimestamp - System.currentTimeMillis()
        if (remainingMillis <= 0) return "Expired"
        val hours = remainingMillis / (1000 * 60 * 60)
        val minutes = (remainingMillis % (1000 * 60 * 60)) / (1000 * 60)
        return String.format("%dh %02dm remaining", hours, minutes)
    }

    // --- Customizable Quick Destination Widget Slots ---

    var widgetSlot1Name: String
        get() = prefs.getString("widget_slot_1_name", "Paris") ?: "Paris"
        set(value) = prefs.edit().putString("widget_slot_1_name", value).apply()

    var widgetSlot1Lat: Double
        get() = prefs.getString("widget_slot_1_lat", "48.8566")?.toDoubleOrNull() ?: 48.8566
        set(value) = prefs.edit().putString("widget_slot_1_lat", value.toString()).apply()

    var widgetSlot1Lon: Double
        get() = prefs.getString("widget_slot_1_lon", "2.3522")?.toDoubleOrNull() ?: 2.3522
        set(value) = prefs.edit().putString("widget_slot_1_lon", value.toString()).apply()

    var widgetSlot2Name: String
        get() = prefs.getString("widget_slot_2_name", "Tokyo") ?: "Tokyo"
        set(value) = prefs.edit().putString("widget_slot_2_name", value).apply()

    var widgetSlot2Lat: Double
        get() = prefs.getString("widget_slot_2_lat", "35.6762")?.toDoubleOrNull() ?: 35.6762
        set(value) = prefs.edit().putString("widget_slot_2_lat", value.toString()).apply()

    var widgetSlot2Lon: Double
        get() = prefs.getString("widget_slot_2_lon", "139.6503")?.toDoubleOrNull() ?: 139.6503
        set(value) = prefs.edit().putString("widget_slot_2_lon", value.toString()).apply()

    var widgetSlot3Name: String
        get() = prefs.getString("widget_slot_3_name", "New York") ?: "New York"
        set(value) = prefs.edit().putString("widget_slot_3_name", value).apply()

    var widgetSlot3Lat: Double
        get() = prefs.getString("widget_slot_3_lat", "40.7128")?.toDoubleOrNull() ?: 40.7128
        set(value) = prefs.edit().putString("widget_slot_3_lat", value.toString()).apply()

    var widgetSlot3Lon: Double
        get() = prefs.getString("widget_slot_3_lon", "-74.0060")?.toDoubleOrNull() ?: -74.0060
        set(value) = prefs.edit().putString("widget_slot_3_lon", value.toString()).apply()

    fun applyTheme(theme: String = appTheme) {
        when (theme) {
            "DARK" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun getOsmTileSource(): ITileSource {
        return when (mapTileSource) {
            "TOPO" -> TileSourceFactory.OpenTopo
            "WIKIMEDIA" -> TileSourceFactory.WIKIMEDIA
            "USGS_SAT" -> TileSourceFactory.USGS_SAT
            else -> TileSourceFactory.MAPNIK
        }
    }

    fun formatSpeed(speedKmh: Float): String {
        return if (distanceUnit == "IMPERIAL") {
            val mph = speedKmh * 0.621371f
            String.format("%.1f MPH", mph)
        } else {
            String.format("%.1f KM/H", speedKmh)
        }
    }

    fun formatDistance(meters: Double): String {
        return if (distanceUnit == "IMPERIAL") {
            val feet = meters * 3.28084
            if (feet >= 5280) {
                String.format("%.2f mi", feet / 5280)
            } else {
                String.format("%.0f ft", feet)
            }
        } else {
            if (meters >= 1000) {
                String.format("%.2f km", meters / 1000)
            } else {
                String.format("%.0f m", meters)
            }
        }
    }
}
