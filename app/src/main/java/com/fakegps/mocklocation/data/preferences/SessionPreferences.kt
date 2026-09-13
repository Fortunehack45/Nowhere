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
        const val REWARD_EXTENSION_DURATION_MILLIS = 2 * 60 * 60 * 1000L // 2 Hours (120 min)

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

    var lastLocationName: String
        get() = prefs.getString("key_last_location_name", "") ?: ""
        set(value) = prefs.edit().putString("key_last_location_name", value).apply()

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

    var isKillSwitchEnabled: Boolean
        get() = prefs.getBoolean("key_kill_switch_enabled", false)
        set(value) = prefs.edit().putBoolean("key_kill_switch_enabled", value).apply()

    var isKillSwitchBypassed: Boolean
        get() = prefs.getBoolean("key_kill_switch_bypassed", false)
        set(value) = prefs.edit().putBoolean("key_kill_switch_bypassed", value).apply()

    var killSwitchBypassApps: Set<String>
        get() = prefs.getStringSet("key_kill_switch_bypass_apps", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("key_kill_switch_bypass_apps", value).apply()

    var lastSelectedGameId: String
        get() = prefs.getString("key_last_selected_game_id", "cod_mobile") ?: "cod_mobile"
        set(value) = prefs.edit().putString("key_last_selected_game_id", value).apply()

    var lastSelectedGameName: String
        get() = prefs.getString("key_last_selected_game_name", "Call of Duty: Mobile / Warzone") ?: "Call of Duty: Mobile / Warzone"
        set(value) = prefs.edit().putString("key_last_selected_game_name", value).apply()

    var lastSelectedGameIcon: String
        get() = prefs.getString("key_last_selected_game_icon", "🎯") ?: "🎯"
        set(value) = prefs.edit().putString("key_last_selected_game_icon", value).apply()

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

    init {
        // Automatic first-run provisioning: Grant 2 hours free timer to every new user
        if (!prefs.contains("key_session_has_initialized")) {
            prefs.edit()
                .putBoolean("key_session_has_initialized", true)
                .putLong("key_session_allocated_duration", DEFAULT_SESSION_DURATION_MILLIS)
                .putLong("key_session_remaining_duration_millis", DEFAULT_SESSION_DURATION_MILLIS)
                .putLong("key_session_expires_timestamp", 0L)
                .putBoolean(KEY_IS_SESSION_ACTIVE, true)
                .putBoolean("key_is_session_running", false)
                .putBoolean("key_is_session_paused", false)
                .putBoolean("key_is_session_expired", false)
                .apply()
        }
    }

    var sessionAllocatedDurationMillis: Long
        get() = prefs.getLong("key_session_allocated_duration", DEFAULT_SESSION_DURATION_MILLIS)
        set(value) = prefs.edit().putLong("key_session_allocated_duration", value).apply()

    var sessionExpiresTimestamp: Long
        get() = prefs.getLong("key_session_expires_timestamp", 0L)
        set(value) = prefs.edit().putLong("key_session_expires_timestamp", value).apply()

    var isSessionExpired: Boolean
        get() = prefs.getBoolean("key_is_session_expired", false)
        set(value) = prefs.edit().putBoolean("key_is_session_expired", value).apply()

    var sessionRemainingDurationMillis: Long
        get() = prefs.getLong("key_session_remaining_duration_millis", -1L)
        set(value) = prefs.edit().putLong("key_session_remaining_duration_millis", value).apply()

    var isSessionRunning: Boolean
        get() = prefs.getBoolean("key_is_session_running", false)
        set(value) = prefs.edit().putBoolean("key_is_session_running", value).apply()

    var isSessionPaused: Boolean
        get() = prefs.getBoolean("key_is_session_paused", false)
        set(value) = prefs.edit().putBoolean("key_is_session_paused", value).apply()

    fun isPremiumActive(): Boolean {
        return com.fakegps.mocklocation.billing.BillingManager.getInstance(context).isPremium.value
    }

    fun hasValidActiveSession(): Boolean {
        if (isPremiumActive()) return true
        if (sessionRemainingDurationMillis < 0L && sessionExpiresTimestamp == 0L) {
            return false
        }
        val remaining = getTimeRemainingMillis()
        return !isSessionExpired && remaining > 0L
    }

    fun startNewSession(durationMillis: Long = DEFAULT_SESSION_DURATION_MILLIS, forceRestart: Boolean = false) {
        val now = System.currentTimeMillis()
        if (isPremiumActive()) {
            isSessionActive = true
            isSessionExpired = false
            return
        }
        if (!forceRestart && !isSessionExpired && getTimeRemainingMillis() > 0L) {
            val remaining = getTimeRemainingMillis()
            // If session was paused or stopped, resume expiry from now
            if (!isSessionActive || sessionExpiresTimestamp <= now) {
                sessionExpiresTimestamp = now + remaining
            }
            sessionRemainingDurationMillis = remaining
            isSessionActive = true
            isSessionRunning = false
            isSessionPaused = false
            return
        }
        sessionAllocatedDurationMillis = durationMillis
        sessionRemainingDurationMillis = durationMillis
        sessionExpiresTimestamp = now + durationMillis
        isSessionExpired = false
        isSessionActive = true
        isSessionRunning = false
        isSessionPaused = false
    }

    fun extendSession(extraMillis: Long = REWARD_EXTENSION_DURATION_MILLIS) {
        if (isPremiumActive()) {
            isSessionActive = true
            isSessionExpired = false
            return
        }
        val now = System.currentTimeMillis()
        val currentRemaining = getTimeRemainingMillis().coerceAtLeast(0L)
        val updatedRemaining = currentRemaining + extraMillis
        sessionRemainingDurationMillis = updatedRemaining
        sessionAllocatedDurationMillis = if (sessionAllocatedDurationMillis > 0L) sessionAllocatedDurationMillis + extraMillis else updatedRemaining
        sessionExpiresTimestamp = if (isSessionActive && sessionExpiresTimestamp > now) {
            sessionExpiresTimestamp + extraMillis
        } else {
            now + updatedRemaining
        }
        isSessionExpired = false
        isSessionActive = true
        isSessionPaused = false
    }

    fun getEffectiveExpiryTimestamp(): Long {
        if (isPremiumActive()) return Long.MAX_VALUE
        if (isSessionActive && sessionExpiresTimestamp > 0L) {
            return sessionExpiresTimestamp
        }
        if (sessionRemainingDurationMillis >= 0L) {
            return System.currentTimeMillis() + sessionRemainingDurationMillis
        }
        return sessionExpiresTimestamp
    }

    fun getTimeRemainingMillis(): Long {
        if (isPremiumActive()) return Long.MAX_VALUE
        if (isSessionRunning && !isSessionPaused && sessionExpiresTimestamp > 0L) {
            val now = System.currentTimeMillis()
            val remaining = sessionExpiresTimestamp - now
            return if (remaining > 0L) remaining else 0L
        }
        if (sessionRemainingDurationMillis >= 0L) {
            return sessionRemainingDurationMillis
        }
        return 0L
    }

    fun decrementRemainingTime(elapsedMillis: Long = 1000L): Long {
        if (isPremiumActive()) return Long.MAX_VALUE
        val current = if (isSessionRunning && !isSessionPaused && sessionExpiresTimestamp > 0L) {
            val wallRemaining = sessionExpiresTimestamp - System.currentTimeMillis()
            if (wallRemaining > 0L) wallRemaining else 0L
        } else {
            val base = if (sessionRemainingDurationMillis >= 0L) sessionRemainingDurationMillis else getTimeRemainingMillis()
            (base - elapsedMillis).coerceAtLeast(0L)
        }
        sessionRemainingDurationMillis = current
        if (current <= 0L) {
            isSessionExpired = true
            isSessionRunning = false
            sessionExpiresTimestamp = 0L
        }
        return current
    }

    fun formatRemainingTime(): String {
        if (isPremiumActive()) return "UNLIMITED"
        val remainingMillis = getTimeRemainingMillis()
        val totalSecs = remainingMillis / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d:%02d", hours, mins, secs)
    }

    fun formatAllocatedDuration(): String {
        if (isPremiumActive()) return "Unlimited"
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
            .remove(KEY_WAYPOINTS_JSON)
            .apply()
    }

    fun resetSessionForTesting() {
        prefs.edit()
            .putBoolean(KEY_IS_SESSION_ACTIVE, false)
            .putBoolean("key_is_session_running", false)
            .putBoolean("key_is_session_paused", false)
            .putBoolean("key_is_session_expired", false)
            .putLong("key_session_expires_timestamp", 0L)
            .putLong("key_session_remaining_duration_millis", -1L)
            .putLong("key_session_allocated_duration", DEFAULT_SESSION_DURATION_MILLIS)
            .remove(KEY_WAYPOINTS_JSON)
            .apply()
    }
}
