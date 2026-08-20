package com.fakegps.mocklocation.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.fakegps.mocklocation.simulator.RoutePoint
import org.json.JSONArray
import org.json.JSONObject

class SessionPreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mock_location_session_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SESSION_DURATION_MILLIS = 2 * 60 * 60 * 1000L // 2 Hours (120 min)
        const val RECONNECT_FALLBACK_DURATION_MILLIS = 20 * 60 * 1000L // 20 Minutes
        const val REWARD_EXTENSION_DURATION_MILLIS = 60 * 60 * 1000L // 1 Hour (60 min)
        const val UNLIMITED_24H_DURATION_MILLIS = 24 * 60 * 60 * 1000L // 24 Hours

        private const val KEY_IS_SESSION_ACTIVE = "key_is_session_active"
        private const val KEY_ACTIVE_MODE = "key_active_mode"
        private const val KEY_LAST_LATITUDE = "key_last_latitude"
        private const val KEY_LAST_LONGITUDE = "key_last_longitude"
        private const val KEY_LAST_ALTITUDE = "key_last_altitude"
        private const val KEY_LAST_SPEED_KMH = "key_last_speed_kmh"
        private const val KEY_IS_LOOPING = "key_is_looping"
        private const val KEY_WAYPOINTS_JSON = "key_waypoints_json"
        private const val KEY_BATTERY_PROMPTED = "key_battery_prompted"
        private const val KEY_OEM_WIDGET_NUDGE_PROMPTED = "key_oem_widget_nudge_prompted"
    }

    var isSessionActive: Boolean
        get() = prefs.getBoolean(KEY_IS_SESSION_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_SESSION_ACTIVE, value).apply()

    var activeMode: String
        get() = prefs.getString(KEY_ACTIVE_MODE, "FIXED") ?: "FIXED"
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODE, value).apply()

    var lastLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAST_LATITUDE, 37.7749.toBits())) // Default San Francisco
        set(value) = prefs.edit().putLong(KEY_LAST_LATITUDE, value.toBits()).apply()

    var lastLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAST_LONGITUDE, (-122.4194).toBits()))
        set(value) = prefs.edit().putLong(KEY_LAST_LONGITUDE, value.toBits()).apply()

    var lastAltitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAST_ALTITUDE, 15.0.toBits()))
        set(value) = prefs.edit().putLong(KEY_LAST_ALTITUDE, value.toBits()).apply()

    var lastSpeedKmh: Float
        get() = prefs.getFloat(KEY_LAST_SPEED_KMH, 20.0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_SPEED_KMH, value).apply()

    var isLooping: Boolean
        get() = prefs.getBoolean(KEY_IS_LOOPING, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOOPING, value).apply()

    var hasPromptedBatteryOptimization: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, value).apply()

    var hasPromptedOemWidgetNudge: Boolean
        get() = prefs.getBoolean(KEY_OEM_WIDGET_NUDGE_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_OEM_WIDGET_NUDGE_PROMPTED, value).apply()

    var hasPromptedExactAlarmPermission: Boolean
        get() = prefs.getBoolean("key_exact_alarm_prompted", false)
        set(value) = prefs.edit().putBoolean("key_exact_alarm_prompted", value).apply()

    var isPersistentBootInjectionEnabled: Boolean
        get() = prefs.getBoolean("key_persistent_boot_injection", true)
        set(value) = prefs.edit().putBoolean("key_persistent_boot_injection", value).apply()

    var isIpMaskingEnabled: Boolean
        get() = prefs.getBoolean("key_ip_masking_enabled", false)
        set(value) = prefs.edit().putBoolean("key_ip_masking_enabled", value).apply()

    var activeIpNodeId: String
        get() = prefs.getString("key_active_ip_node_id", "us_nyc") ?: "us_nyc"
        set(value) = prefs.edit().putString("key_active_ip_node_id", value).apply()

    var autoMatchIpWithGps: Boolean
        get() = prefs.getBoolean("key_auto_match_ip_with_gps", true)
        set(value) = prefs.edit().putBoolean("key_auto_match_ip_with_gps", value).apply()

    var routeTotalDistanceMeters: Double
        get() = Double.fromBits(prefs.getLong("key_route_total_distance", 0L))
        set(value) = prefs.edit().putLong("key_route_total_distance", value.toBits()).apply()

    var routeCoveredDistanceMeters: Double
        get() = Double.fromBits(prefs.getLong("key_route_covered_distance", 0L))
        set(value) = prefs.edit().putLong("key_route_covered_distance", value.toBits()).apply()

    var routeRemainingDistanceMeters: Double
        get() = Double.fromBits(prefs.getLong("key_route_remaining_distance", 0L))
        set(value) = prefs.edit().putLong("key_route_remaining_distance", value.toBits()).apply()

    fun saveWaypoints(waypoints: List<RoutePoint>) {
        val jsonArray = JSONArray()
        for (wp in waypoints) {
            val obj = JSONObject().apply {
                put("lat", wp.latitude)
                put("lon", wp.longitude)
                put("alt", wp.altitude)
                put("stopSec", wp.stopDurationSeconds)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_WAYPOINTS_JSON, jsonArray.toString()).apply()
    }

    fun getWaypoints(): List<RoutePoint> {
        val jsonString = prefs.getString(KEY_WAYPOINTS_JSON, null) ?: return emptyList()
        val list = mutableListOf<RoutePoint>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    RoutePoint(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        altitude = obj.optDouble("alt", 0.0),
                        stopDurationSeconds = obj.optInt("stopSec", 0)
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse failures
        }
        return list
    }

    // --- Session Connection Duration & Countdown Timer Management ---

    var sessionAllocatedDurationMillis: Long
        get() = prefs.getLong("key_session_allocated_duration", DEFAULT_SESSION_DURATION_MILLIS)
        set(value) = prefs.edit().putLong("key_session_allocated_duration", value).apply()

    var sessionExpiresTimestamp: Long
        get() = prefs.getLong("key_session_expires_timestamp", 0L)
        set(value) = prefs.edit().putLong("key_session_expires_timestamp", value).apply()

    var isSessionExpired: Boolean
        get() = prefs.getBoolean("key_is_session_expired", false)
        set(value) = prefs.edit().putBoolean("key_is_session_expired", value).apply()

    fun hasValidActiveSession(): Boolean {
        val remaining = getTimeRemainingMillis()
        return isSessionActive && !isSessionExpired && remaining > 0L
    }

    fun startNewSession(durationMillis: Long = DEFAULT_SESSION_DURATION_MILLIS, forceRestart: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceRestart && hasValidActiveSession()) {
            // Keep existing unexpired remaining duration and expiry timestamp intact across updates/restarts
            return
        }
        sessionAllocatedDurationMillis = durationMillis
        sessionExpiresTimestamp = now + durationMillis
        isSessionExpired = false
        isSessionActive = true
    }

    fun extendSession(extraMillis: Long = REWARD_EXTENSION_DURATION_MILLIS) {
        val now = System.currentTimeMillis()
        val currentExpiry = if (sessionExpiresTimestamp > now) sessionExpiresTimestamp else now
        sessionExpiresTimestamp = currentExpiry + extraMillis
        sessionAllocatedDurationMillis += extraMillis
        isSessionExpired = false
        isSessionActive = true
    }

    fun getEffectiveExpiryTimestamp(): Long {
        return sessionExpiresTimestamp
    }

    fun getTimeRemainingMillis(): Long {
        val remaining = getEffectiveExpiryTimestamp() - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun formatRemainingTime(): String {
        val remainingMillis = getTimeRemainingMillis()
        val totalSecs = remainingMillis / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d:%02d", hours, mins, secs)
    }

    fun formatAllocatedDuration(): String {
        val totalSecs = sessionAllocatedDurationMillis / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        return if (hours > 0) {
            String.format("%dh %02dm", hours, mins)
        } else {
            String.format("%dm", mins)
        }
    }

    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_IS_SESSION_ACTIVE, false)
            .putBoolean("key_is_session_expired", false)
            .remove(KEY_WAYPOINTS_JSON)
            .apply()
    }
}
