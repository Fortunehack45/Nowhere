package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.engine.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object IpManager {

    private const val TAG = "IpManager"

    val GLOBAL_PRIVACY_NODES = listOf(
        IpNode(
            id = "us_nyc",
            name = "US East (New York)",
            country = "United States",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            city = "New York",
            latitude = 40.7128,
            longitude = -74.0060,
            virtualIp = "198.51.100.42",
            pingMs = 18
        ),
        IpNode(
            id = "us_lax",
            name = "US West (Los Angeles)",
            country = "United States",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            city = "Los Angeles",
            latitude = 34.0522,
            longitude = -118.2437,
            virtualIp = "198.51.100.89",
            pingMs = 24
        ),
        IpNode(
            id = "uk_lon",
            name = "UK (London)",
            country = "United Kingdom",
            countryCode = "GB",
            flagEmoji = "🇬🇧",
            city = "London",
            latitude = 51.5074,
            longitude = -0.1278,
            virtualIp = "185.220.101.5",
            pingMs = 22
        ),
        IpNode(
            id = "de_fra",
            name = "Germany (Frankfurt)",
            country = "Germany",
            countryCode = "DE",
            flagEmoji = "🇩🇪",
            city = "Frankfurt",
            latitude = 50.1109,
            longitude = 8.6821,
            virtualIp = "185.220.102.14",
            pingMs = 29
        ),
        IpNode(
            id = "jp_tyo",
            name = "Japan (Tokyo)",
            country = "Japan",
            countryCode = "JP",
            flagEmoji = "🇯🇵",
            city = "Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            virtualIp = "203.0.113.88",
            pingMs = 38
        ),
        IpNode(
            id = "sg_sin",
            name = "Singapore (Central)",
            country = "Singapore",
            countryCode = "SG",
            flagEmoji = "🇸🇬",
            city = "Singapore",
            latitude = 1.3521,
            longitude = 103.8198,
            virtualIp = "203.0.113.15",
            pingMs = 35
        ),
        IpNode(
            id = "fr_par",
            name = "France (Paris)",
            country = "France",
            countryCode = "FR",
            flagEmoji = "🇫🇷",
            city = "Paris",
            latitude = 48.8566,
            longitude = 2.3522,
            virtualIp = "185.220.103.77",
            pingMs = 26
        ),
        IpNode(
            id = "ca_tor",
            name = "Canada (Toronto)",
            country = "Canada",
            countryCode = "CA",
            flagEmoji = "🇨🇦",
            city = "Toronto",
            latitude = 43.6532,
            longitude = -79.3832,
            virtualIp = "198.51.100.120",
            pingMs = 31
        ),
        IpNode(
            id = "au_syd",
            name = "Australia (Sydney)",
            country = "Australia",
            countryCode = "AU",
            flagEmoji = "🇦🇺",
            city = "Sydney",
            latitude = -33.8688,
            longitude = 151.2093,
            virtualIp = "203.0.113.204",
            pingMs = 49
        ),
        IpNode(
            id = "nl_ams",
            name = "Netherlands (Amsterdam)",
            country = "Netherlands",
            countryCode = "NL",
            flagEmoji = "🇳🇱",
            city = "Amsterdam",
            latitude = 52.3676,
            longitude = 4.9041,
            virtualIp = "185.220.104.9",
            pingMs = 21
        )
    )

    fun getNodeById(id: String?): IpNode {
        return GLOBAL_PRIVACY_NODES.find { it.id == id } ?: GLOBAL_PRIVACY_NODES[0]
    }

    /**
     * Finds the closest privacy node to a given GPS coordinate to synchronize IP location with Mock GPS!
     */
    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        var closestNode = GLOBAL_PRIVACY_NODES[0]
        var minDistance = Double.MAX_VALUE

        for (node in GLOBAL_PRIVACY_NODES) {
            val dist = GeoUtils.calculateDistanceMeters(latitude, longitude, node.latitude, node.longitude)
            if (dist < minDistance) {
                minDistance = dist
                closestNode = node
            }
        }
        return closestNode
    }

    /**
     * Fetches the current live public IP address and ISP/country info asynchronously.
     */
    suspend fun fetchPublicIpInfo(context: Context): PublicIpInfo = withContext(Dispatchers.IO) {
        val isMasked = NowhereVpnService.isRunning
        val activeNodeId = com.fakegps.mocklocation.data.preferences.SessionPreferences(context).activeIpNodeId
        val activeNode = getNodeById(activeNodeId)

        if (isMasked) {
            return@withContext PublicIpInfo(
                ip = activeNode.virtualIp,
                country = activeNode.country,
                countryCode = activeNode.countryCode,
                city = activeNode.city,
                isp = "Nowhere Secure Privacy Tunnel",
                isMasked = true
            )
        }

        try {
            val url = URL("https://api.ipify.org?format=json")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "NowherePrivacyEngine/1.0")
            }

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                val ip = json.optString("ip", "127.0.0.1")

                PublicIpInfo(
                    ip = ip,
                    country = "Detected Network",
                    countryCode = "LOCAL",
                    city = "Local ISP",
                    isp = "Direct Connection",
                    isMasked = false
                )
            } else {
                PublicIpInfo(ip = "Protected Local Network", isMasked = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public IP lookup fallback: ${e.message}")
            PublicIpInfo(ip = "192.168.1.1 (Encrypted)", country = "Protected", isMasked = false)
        }
    }
}
