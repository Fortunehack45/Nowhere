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
import java.util.Locale

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

        val appContext = context.applicationContext
        val candidateUrls = buildCandidateUrls(appContext, hostUrl)
        var activeWorkingUrl = candidateUrls.firstOrNull() ?: DEFAULT_HOST_URL

        Log.i(TAG, "Starting Hotspot GPS Client sync with candidates: $candidateUrls")
        _syncState.value = SyncState.Connecting(activeWorkingUrl)

        clientEngine = MockLocationEngine(appContext)
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
                    // Try primary working URL first
                    var locationData = fetchHostLocation(activeWorkingUrl)

                    // If primary fails during initial connection or drop, sweep candidate gateway URLs
                    if (locationData == null) {
                        for (candidate in candidateUrls) {
                            if (candidate == activeWorkingUrl) continue
                            val testData = fetchHostLocation(candidate)
                            if (testData != null) {
                                activeWorkingUrl = candidate
                                locationData = testData
                                Log.i(TAG, "Discovered working host phone at $activeWorkingUrl")
                                break
                            }
                        }
                    }

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
                            hostUrl = activeWorkingUrl,
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            speedKmh = speedKmh,
                            bearing = bearing
                        )
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures > 4) {
                            _syncState.value = SyncState.Error("Searching for Host Phone at $activeWorkingUrl... (Ensure phone is connected to host hotspot)")
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

    private fun buildCandidateUrls(context: Context, initialHostUrl: String): List<String> {
        val list = mutableListOf<String>()

        fun normalizeUrl(raw: String, defaultPort: Int = 8088): String {
            var url = raw.trim()
            if (url.isEmpty()) return "http://192.168.43.1:$defaultPort/location.json"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            val hostPart = url.substringAfter("://").substringBefore("/")
            if (!hostPart.contains(":")) {
                url = "http://$hostPart:$defaultPort"
            }
            return if (!url.endsWith("/location.json") && !url.endsWith("/api/location")) {
                if (url.endsWith("/")) "${url}location.json" else "$url/location.json"
            } else {
                url
            }
        }

        if (initialHostUrl.isNotBlank()) {
            list.add(normalizeUrl(initialHostUrl))
        }

        // 1. Detect DHCP Gateway from active Wi-Fi / Hotspot connection
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ipInt = dhcp.gateway
                val gatewayIp = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (gatewayIp != "0.0.0.0" && !gatewayIp.startsWith("127.")) {
                    list.add(normalizeUrl(gatewayIp, 8088))
                    list.add(normalizeUrl(gatewayIp, 8089))
                    list.add(normalizeUrl(gatewayIp, 8090))
                }
            }
        } catch (ignored: Exception) {}

        // 2. Common Android Hotspot Gateway defaults
        val defaultGateways = listOf("192.168.43.1", "192.168.44.1", "192.168.49.1", "192.168.50.1", "10.0.0.1", "192.168.1.1")
        for (gw in defaultGateways) {
            list.add(normalizeUrl(gw, 8088))
            list.add(normalizeUrl(gw, 8089))
        }

        return list.distinct()
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
                connectTimeout = 2000
                readTimeout = 2000
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
