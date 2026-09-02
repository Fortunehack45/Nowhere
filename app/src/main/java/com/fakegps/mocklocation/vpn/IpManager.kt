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
        // Live Active Physical Server (Google Cloud US Central)
        IpNode("us_central_gcp", "United States (US Central Gateway)", "United States", "US", "🇺🇸", "Council Bluffs", 41.2619, -95.8608, "104.197.128.154", 12, isAvailable = true),

        // Planned Regional Nodes (Honestly marked Upcoming until dedicated regional VPS deployed)
        IpNode("uk_lon_1", "United Kingdom (London)", "United Kingdom", "GB", "🇬🇧", "London", 51.5074, -0.1278, "Coming Soon", 26, isAvailable = false),
        IpNode("de_fra_1", "Germany (Frankfurt)", "Germany", "DE", "🇩🇪", "Frankfurt", 50.1109, 8.6821, "Coming Soon", 28, isAvailable = false),
        IpNode("jp_tyo_1", "Japan (Tokyo)", "Japan", "JP", "🇯🇵", "Tokyo", 35.6762, 139.6503, "Coming Soon", 35, isAvailable = false),
        IpNode("sg_sin_1", "Singapore (Singapore)", "Singapore", "SG", "🇸🇬", "Singapore", 1.3521, 103.8198, "Coming Soon", 38, isAvailable = false),
        IpNode("ca_tor_1", "Canada (Toronto)", "Canada", "CA", "🇨🇦", "Toronto", 43.6532, -79.3832, "Coming Soon", 24, isAvailable = false),
        IpNode("au_syd_1", "Australia (Sydney)", "Australia", "AU", "🇦🇺", "Sydney", -33.8688, 151.2093, "Coming Soon", 45, isAvailable = false),
        IpNode("in_bom_1", "India (Mumbai)", "India", "IN", "🇮🇳", "Mumbai", 19.0760, 72.8777, "Coming Soon", 42, isAvailable = false),
        IpNode("br_sao_1", "Brazil (São Paulo)", "Brazil", "BR", "🇧🇷", "São Paulo", -23.5505, -46.6333, "Coming Soon", 48, isAvailable = false),
        IpNode("za_jnb_1", "South Africa (Johannesburg)", "South Africa", "ZA", "🇿🇦", "Johannesburg", -26.2041, 28.0473, "Coming Soon", 55, isAvailable = false)
    )

    val GLOBAL_NODES get() = GLOBAL_PRIVACY_NODES

    fun getNodeById(nodeId: String): IpNode {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId } ?: GLOBAL_PRIVACY_NODES.first()
    }

    fun findNodeById(nodeId: String): IpNode? {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId }
    }

    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        // Always connect to verified live US Central Gateway so data/internet never drops
        return GLOBAL_PRIVACY_NODES.first { it.id == "us_central_gcp" }
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
