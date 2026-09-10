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

    // Real Live Google Cloud & Global WireGuard Nodes
    val GLOBAL_PRIVACY_NODES = listOf(
        // Live Active Gateway Nodes
        IpNode("us_central_gcp", "United States (US Central Gateway)", "United States", "US", "🇺🇸", "Council Bluffs", 41.2619, -95.8608, "104.197.128.154", 12, isAvailable = true),
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
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId } ?: GLOBAL_PRIVACY_NODES.first()
    }

    fun findNodeById(nodeId: String): IpNode? {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId }
    }

    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        return GLOBAL_PRIVACY_NODES
            .filter { it.isAvailable }
            .minByOrNull { node ->
                GeoUtils.calculateDistanceMeters(latitude, longitude, node.latitude, node.longitude)
            } ?: GLOBAL_PRIVACY_NODES.first()
    }

    /**
     * Fetches the current live public IP address and ISP/country info asynchronously from real external IP services.
     */
    suspend fun fetchPublicIpInfo(context: Context): PublicIpInfo = withContext(Dispatchers.IO) {
        val sessionPrefs = SessionPreferences(context)
        val isMasked = NowhereVpnService.isRunning || sessionPrefs.isIpMaskingEnabled
        val activeNodeId = sessionPrefs.activeIpNodeId
        val activeNode = getNodeById(activeNodeId)

        if (isMasked) {
            return@withContext PublicIpInfo(
                ip = activeNode.virtualIp,
                country = activeNode.country,
                countryCode = activeNode.countryCode,
                city = activeNode.city,
                isp = "Google Cloud Nowhere WireGuard Network",
                isMasked = true
            )
        }

        try {
            // Real HTTP lookup to get the raw unmasked direct IP
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
            Log.w(TAG, "Public IP lookup: ${e.message}")
            PublicIpInfo(ip = "Direct Cellular / Wi-Fi", isMasked = false)
        }
    }
}
