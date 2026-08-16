package com.fakegps.mocklocation.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.fakegps.mocklocation.simulator.RoutePoint
import org.json.JSONArray
import org.json.JSONObject

class SessionPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mock_location_session_prefs", Context.MODE_PRIVATE)

    companion object {
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

    var isPersistentBootInjectionEnabled: Boolean
        get() = prefs.getBoolean("key_persistent_boot_injection", true)
        set(value) = prefs.edit().putBoolean("key_persistent_boot_injection", value).apply()

    fun saveWaypoints(waypoints: List<RoutePoint>) {
        val jsonArray = JSONArray()
        for (wp in waypoints) {
            val obj = JSONObject().apply {
                put("lat", wp.latitude)
                put("lon", wp.longitude)
                put("alt", wp.altitude)
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
                        altitude = obj.optDouble("alt", 0.0)
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse failures
        }
        return list
    }

    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_IS_SESSION_ACTIVE, false)
            .remove(KEY_WAYPOINTS_JSON)
            .apply()
    }
}
