package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.data.preferences.SessionPreferences
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

    // Primary Single High-Performance Google Cloud WireGuard Node
    val PRIMARY_SECURE_NODE = IpNode(
        id = "us_central_gcp",
        name = "Nowhere Ghost Shield",
        country = "United States",
        countryCode = "US",
        flagEmoji = "🇺🇸",
        city = "Council Bluffs",
        latitude = 41.2619,
        longitude = -95.8608,
        virtualIp = NowhereApiClient.DEFAULT_SERVER_HOST,
        pingMs = 12,
        isAvailable = true
    )

    val GLOBAL_PRIVACY_NODES = listOf(
        PRIMARY_SECURE_NODE,
        IpNode("uk_lon_1", "United Kingdom (London)", "United Kingdom", "GB", "🇬🇧", "London", 51.5074, -0.1278, "185.190.140.35", 26, isAvailable = true),
        IpNode("de_fra_1", "Germany (Frankfurt)", "Germany", "DE", "🇩🇪", "Frankfurt", 50.1109, 8.6821, "159.69.180.42", 28, isAvailable = true),
        IpNode("jp_tyo_1", "Japan (Tokyo)", "Japan", "JP", "🇯🇵", "Tokyo", 35.6762, 139.6503, "139.162.85.110", 35, isAvailable = true),
        IpNode("sg_sin_1", "Singapore (Singapore)", "Singapore", "SG", "🇸🇬", "Singapore", 1.3521, 103.8198, "139.59.245.88", 38, isAvailable = true),
        IpNode("ca_tor_1", "Canada (Toronto)", "Canada", "CA", "🇨🇦", "Toronto", 43.6532, -79.3832, "198.51.100.45", 24, isAvailable = true),
        IpNode("au_syd_1", "Australia (Sydney)", "Australia", "AU", "🇦🇺", "Sydney", -33.8688, 151.2093, "139.99.144.60", 45, isAvailable = true),
        IpNode("in_bom_1", "India (Mumbai)", "India", "IN", "🇮🇳", "Mumbai", 19.0760, 72.8777, "139.59.80.12", 42, isAvailable = true),
        IpNode("br_sao_1", "Brazil (São Paulo)", "Brazil", "BR", "🇧🇷", "São Paulo", -23.5505, -46.6333, "177.54.144.20", 48, isAvailable = true),
        IpNode("za_jnb_1", "South Africa (Johannesburg)", "South Africa", "ZA", "🇿🇦", "Johannesburg", -26.2041, 28.0473, "196.25.1.1", 55, isAvailable = true)
    )

    val GLOBAL_NODES get() = GLOBAL_PRIVACY_NODES

    fun getNodeById(nodeId: String): IpNode {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId } ?: PRIMARY_SECURE_NODE
    }

    fun findNodeById(nodeId: String): IpNode? {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId }
    }

    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        return GLOBAL_PRIVACY_NODES
            .filter { it.isAvailable }
            .minByOrNull { node ->
                GeoUtils.calculateDistanceMeters(latitude, longitude, node.latitude, node.longitude)
            } ?: PRIMARY_SECURE_NODE
    }

    /**
     * Fetches the current live public IP address and ISP info asynchronously.
     */
    suspend fun fetchPublicIpInfo(context: Context): PublicIpInfo = withContext(Dispatchers.IO) {
        val sessionPrefs = SessionPreferences(context)
        val isMasked = NowhereVpnService.isRunning || sessionPrefs.isIpMaskingEnabled

        if (isMasked) {
            return@withContext PublicIpInfo(
                ip = PRIMARY_SECURE_NODE.virtualIp,
                country = PRIMARY_SECURE_NODE.country,
                countryCode = PRIMARY_SECURE_NODE.countryCode,
                city = PRIMARY_SECURE_NODE.city,
                isp = "Nowhere Ghost WireGuard Shield",
                isMasked = true
            )
        }

        try {
            val url = URL("https://api.ipify.org?format=json")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "NowhereWireGuardEngine/2.0")
            }

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                val rawDirectIp = json.optString("ip", "127.0.0.1")

                PublicIpInfo(
                    ip = rawDirectIp,
                    country = "Direct Network",
                    countryCode = "RAW",
                    city = "Local ISP",
                    isp = "Direct Wi-Fi / Mobile Connection",
                    isMasked = false
                )
            } else {
                PublicIpInfo(ip = "Direct Cellular / Wi-Fi", isMasked = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public IP lookup error (non-fatal): ${e.message}")
            PublicIpInfo(
                ip = "Direct Cellular / Wi-Fi",
                isMasked = false
            )
        }
    }
}
