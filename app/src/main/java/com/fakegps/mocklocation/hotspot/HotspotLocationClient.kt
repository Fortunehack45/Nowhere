package com.fakegps.mocklocation.hotspot

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.engine.MockLocationEngine
import com.fakegps.mocklocation.util.PermissionHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hotspot Location Client for Android devices (BETA).
 * Connects to a Host Android phone running Nowhere Hotspot GPS Server,
 * continuously receives spoofed coordinates, and injects them directly into
 * this device's Android Mock Location Engine (giving system-wide spoofing to all apps on this phone).
 */
object HotspotLocationClient {

    private const val TAG = "HotspotLocationClient"
    const val DEFAULT_HOST_URL = "http://192.168.43.1:8088/location.json"

    sealed class SyncState {
        object Idle : SyncState()
        data class Connecting(val url: String) : SyncState()
        data class Synced(
            val hostUrl: String,
            val latitude: Double,
            val longitude: Double,
            val altitude: Double,
            val speedKmh: Float,
            val bearing: Float
        ) : SyncState()
        data class Error(val message: String, val needsMockPermission: Boolean = false) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var clientEngine: MockLocationEngine? = null

    fun isSyncing(): Boolean {
        return syncJob?.isActive == true && _syncState.value is SyncState.Synced
    }

    fun startSync(context: Context, hostUrl: String = DEFAULT_HOST_URL) {
        stopSync()

        // Verify Mock App permission on this client Android device
        if (!PermissionHelper.isMockLocationEnabled(context)) {
            _syncState.value = SyncState.Error(
                "Mock Location permission not granted on this phone. Please select Nowhere in Developer Options -> Select mock location app.",
                needsMockPermission = true
            )
            return
        }

        var cleanUrl = hostUrl.trim()
        if (cleanUrl.isEmpty()) {
            cleanUrl = "http://192.168.43.1:8088"
        }

        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }

        val hostPart = cleanUrl.substringAfter("://").substringBefore("/")
        if (!hostPart.contains(":") && !cleanUrl.endsWith(".json")) {
            cleanUrl = "http://$hostPart:8088"
        }

        val fullUrl = if (!cleanUrl.endsWith("/location.json") && !cleanUrl.endsWith("/api/location")) {
            if (cleanUrl.endsWith("/")) "${cleanUrl}location.json" else "$cleanUrl/location.json"
        } else {
            cleanUrl
        }

        Log.i(TAG, "Starting Hotspot GPS Client sync with host: $fullUrl")
        _syncState.value = SyncState.Connecting(fullUrl)

        clientEngine = MockLocationEngine(context.applicationContext)
        val initResult = clientEngine?.initialize()
        if (initResult?.isFailure == true) {
            val errorMsg = initResult.exceptionOrNull()?.message ?: "Failed to initialize test provider"
            Log.w(TAG, "MockLocationEngine init failure: $errorMsg")
            _syncState.value = SyncState.Error("Test Provider Error: $errorMsg. Ensure Nowhere is chosen in Developer Options.")
            return
        }

        syncJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var consecutiveFailures = 0

            while (isActive) {
                try {
                    val locationData = fetchHostLocation(fullUrl)
                    if (locationData != null) {
                        consecutiveFailures = 0
                        val lat = locationData.optDouble("latitude", 0.0)
                        val lon = locationData.optDouble("longitude", 0.0)
                        val alt = locationData.optDouble("altitude", 15.0)
                        val speedMps = locationData.optDouble("speedMps", 0.0).toFloat()
                        val speedKmh = locationData.optDouble("speedKmh", 0.0).toFloat()
                        val bearing = locationData.optDouble("bearing", 0.0).toFloat()

                        // Inject into Android test provider on this client device
                        clientEngine?.setLocation(
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            speed = speedMps,
                            bearing = bearing,
                            applyStationaryJitter = false
                        )

                        _syncState.value = SyncState.Synced(
                            hostUrl = fullUrl,
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            speedKmh = speedKmh,
                            bearing = bearing
                        )
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures > 4) {
                            _syncState.value = SyncState.Error("Searching for Host Phone at $fullUrl... (Ensure phone is connected to host hotspot)")
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures > 4) {
                        _syncState.value = SyncState.Error("Connection issue: ${e.message}")
                    }
                }

                delay(500L) // 500ms real-time sync heartbeat
            }
        }
    }

    fun stopSync() {
        Log.i(TAG, "Stopping Hotspot GPS Client sync")
        syncJob?.cancel()
        syncJob = null
        try {
            clientEngine?.stop()
        } catch (ignored: Exception) {}
        clientEngine = null
        _syncState.value = SyncState.Idle
    }

    private fun fetchHostLocation(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                JSONObject(response)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {}
        }
    }
}
