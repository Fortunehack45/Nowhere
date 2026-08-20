package com.fakegps.mocklocation.hotspot

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.engine.MockLocationEngine
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
 * Hotspot Location Client for Android devices.
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
        data class Error(val message: String) : SyncState()
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

        val cleanUrl = if (!hostUrl.startsWith("http://") && !hostUrl.startsWith("https://")) {
            "http://$hostUrl"
        } else {
            hostUrl
        }

        val fullUrl = if (!cleanUrl.endsWith("/location.json") && !cleanUrl.endsWith("/api/location")) {
            if (cleanUrl.endsWith("/")) "${cleanUrl}location.json" else "$cleanUrl/location.json"
        } else {
            cleanUrl
        }

        Log.i(TAG, "Starting Hotspot GPS Client sync with host: $fullUrl")
        _syncState.value = SyncState.Connecting(fullUrl)

        clientEngine = MockLocationEngine(context.applicationContext)
        try {
            clientEngine?.initialize()
        } catch (e: Exception) {
            Log.w(TAG, "Engine initialize warning: ${e.message}")
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
                        if (consecutiveFailures > 5) {
                            _syncState.value = SyncState.Error("Lost connection to Host Phone ($fullUrl). Reconnecting...")
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures > 5) {
                        _syncState.value = SyncState.Error("Connection error: ${e.message}")
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
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
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
