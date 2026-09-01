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
        IpNode("us_central_gcp", "United States (US Central)", "United States", "US", "🇺🇸", "Council Bluffs", 41.2619, -95.8608, "104.197.128.154", 12),
        IpNode("us_nyc_1", "United States (New York)", "United States", "US", "🇺🇸", "New York", 40.7128, -74.0060, "104.197.128.154", 16),
        IpNode("us_sfo_1", "United States (San Francisco)", "United States", "US", "🇺🇸", "San Francisco", 37.7749, -122.4194, "104.197.128.154", 18),
        IpNode("us_chi_1", "United States (Chicago)", "United States", "US", "🇺🇸", "Chicago", 41.8781, -87.6298, "104.197.128.154", 15),
        IpNode("us_mia_1", "United States (Miami)", "United States", "US", "🇺🇸", "Miami", 25.7617, -80.1918, "104.197.128.154", 20),
        IpNode("ca_tor_1", "Canada (Toronto)", "Canada", "CA", "🇨🇦", "Toronto", 43.6532, -79.3832, "104.197.128.154", 24),
        IpNode("uk_lon_1", "United Kingdom (London)", "United Kingdom", "GB", "🇬🇧", "London", 51.5074, -0.1278, "104.197.128.154", 26),
        IpNode("de_fra_1", "Germany (Frankfurt)", "Germany", "DE", "🇩🇪", "Frankfurt", 50.1109, 8.6821, "104.197.128.154", 28),
        IpNode("fr_par_1", "France (Paris)", "France", "FR", "🇫🇷", "Paris", 48.8566, 2.3522, "104.197.128.154", 27),
        IpNode("nl_ams_1", "Netherlands (Amsterdam)", "Netherlands", "NL", "🇳🇱", "Amsterdam", 52.3676, 4.9041, "104.197.128.154", 25),
        IpNode("ch_zrh_1", "Switzerland (Zurich)", "Switzerland", "CH", "🇨🇭", "Zurich", 47.3769, 8.5417, "104.197.128.154", 29),
        IpNode("se_sto_1", "Sweden (Stockholm)", "Sweden", "SE", "🇸🇪", "Stockholm", 59.3293, 18.0686, "104.197.128.154", 32),
        IpNode("jp_tyo_1", "Japan (Tokyo)", "Japan", "JP", "🇯🇵", "Tokyo", 35.6762, 139.6503, "104.197.128.154", 35),
        IpNode("sg_sin_1", "Singapore (Singapore)", "Singapore", "SG", "🇸🇬", "Singapore", 1.3521, 103.8198, "104.197.128.154", 38),
        IpNode("au_syd_1", "Australia (Sydney)", "Australia", "AU", "🇦🇺", "Sydney", -33.8688, 151.2093, "104.197.128.154", 45),
        IpNode("in_bom_1", "India (Mumbai)", "India", "IN", "🇮🇳", "Mumbai", 19.0760, 72.8777, "104.197.128.154", 42),
        IpNode("br_sao_1", "Brazil (São Paulo)", "Brazil", "BR", "🇧🇷", "São Paulo", -23.5505, -46.6333, "104.197.128.154", 48),
        IpNode("za_jnb_1", "South Africa (Johannesburg)", "South Africa", "ZA", "🇿🇦", "Johannesburg", -26.2041, 28.0473, "104.197.128.154", 55)
    )

    val GLOBAL_NODES get() = GLOBAL_PRIVACY_NODES

    fun getNodeById(nodeId: String): IpNode {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId } ?: GLOBAL_PRIVACY_NODES.first()
    }

    fun findNodeById(nodeId: String): IpNode? {
        return GLOBAL_PRIVACY_NODES.find { it.id == nodeId }
    }

    fun findClosestNodeForCoordinates(latitude: Double, longitude: Double): IpNode {
        var closestNode = GLOBAL_PRIVACY_NODES.first()
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
