package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object NowhereApiClient {

    private const val TAG = "NowhereApiClient"

    // Production Google Cloud VPS Server
    const val DEFAULT_SERVER_HOST = "104.197.128.154"
    const val DEFAULT_BASE_URL = "http://104.197.128.154:8080"
    const val DEFAULT_API_KEY = "nowhere_live_prod_key_77a9c84e1b"

    data class TunnelResponse(
        val nodeId: String,
        val country: String,
        val countryName: String,
        val city: String,
        val serverPubkey: String,
        val endpoint: String,
        val assignedIp: String,
        val clientPrivateKey: String?,
        val clientPublicKey: String,
        val allowedIps: List<String>,
        val dns: List<String>,
        val mtu: Int,
        val isGameBoosted: Boolean = false,
        val gameName: String? = null,
        val estimatedPingMs: Int? = null
    )

    data class GameItem(
        val id: String,
        val name: String,
        val category: String,
        val icon: String,
        val packetQos: String,
        val estimatedPingMs: Int
    )

    private fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("nowhere_vpn_client_prefs", Context.MODE_PRIVATE)
        return prefs.getString("custom_backend_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    private fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("nowhere_vpn_client_prefs", Context.MODE_PRIVATE)
        return prefs.getString("custom_api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY
    }

    /**
     * Connects to a WireGuard node/region on the live control-plane.
     */
    suspend fun connectTunnel(
        context: Context,
        nodeId: String? = null,
        country: String? = null,
        clientPublicKey: String? = null
    ): Result<TunnelResponse> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "${getBaseUrl(context)}/api/v1/connect"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", getApiKey(context))
            }

            val jsonBody = JSONObject().apply {
                if (!nodeId.isNullOrEmpty()) put("node_id", nodeId)
                if (!country.isNullOrEmpty()) put("country", country)
                if (!clientPublicKey.isNullOrEmpty()) put("client_public_key", clientPublicKey)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.use { it.readText() }
                val json = JSONObject(responseText)

                val allowedIpsList = mutableListOf<String>()
                val allowedIpsArr = json.optJSONArray("allowed_ips")
                if (allowedIpsArr != null) {
                    for (i in 0 until allowedIpsArr.length()) {
                        allowedIpsList.add(allowedIpsArr.getString(i))
                    }
                } else {
                    allowedIpsList.add("0.0.0.0/0")
                }

                val dnsList = mutableListOf<String>()
                val dnsArr = json.optJSONArray("dns")
                if (dnsArr != null) {
                    for (i in 0 until dnsArr.length()) {
                        dnsList.add(dnsArr.getString(i))
                    }
                } else {
                    dnsList.add("1.1.1.1")
                }

                val resp = TunnelResponse(
                    nodeId = json.optString("node_id", "us_nyc_1"),
                    country = json.optString("country", "US"),
                    countryName = json.optString("country_name", "United States"),
                    city = json.optString("city", "New York"),
                    serverPubkey = json.optString("server_pubkey", ""),
                    endpoint = json.optString("endpoint", "$DEFAULT_SERVER_HOST:51820"),
                    assignedIp = json.optString("assigned_ip", "10.8.0.2/32"),
                    clientPrivateKey = if (json.has("client_private_key")) json.getString("client_private_key") else null,
                    clientPublicKey = json.optString("client_public_key", ""),
                    allowedIps = allowedIpsList,
                    dns = dnsList,
                    mtu = json.optInt("mtu", 1420)
                )
                Result.success(resp)
            } else {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.w(TAG, "Backend connect failed: $errorMsg")
                Result.failure(Exception("Backend connect error ($responseCode): $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network failure reaching Nowhere VPN backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Optimizes low-latency route for a specific game (Game Booster).
     */
    suspend fun optimizeGame(
        context: Context,
        gameId: String,
        regionCode: String? = null,
        clientPublicKey: String? = null
    ): Result<TunnelResponse> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "${getBaseUrl(context)}/api/v1/game-boost/optimize"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", getApiKey(context))
            }

            val jsonBody = JSONObject().apply {
                put("game_id", gameId)
                if (!regionCode.isNullOrEmpty()) put("region_code", regionCode)
                if (!clientPublicKey.isNullOrEmpty()) put("client_public_key", clientPublicKey)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)

                val resp = TunnelResponse(
                    nodeId = json.optString("node_id", "us_nyc_1"),
                    country = json.optString("country", "US"),
                    countryName = json.optString("country_name", "United States"),
                    city = json.optString("city", "New York"),
                    serverPubkey = json.optString("server_pubkey", ""),
                    endpoint = json.optString("endpoint", "$DEFAULT_SERVER_HOST:51820"),
                    assignedIp = json.optString("assigned_ip", "10.8.0.2/32"),
                    clientPrivateKey = if (json.has("client_private_key")) json.getString("client_private_key") else null,
                    clientPublicKey = json.optString("client_public_key", ""),
                    allowedIps = listOf("0.0.0.0/0"),
                    dns = listOf("1.1.1.1"),
                    mtu = json.optInt("mtu", 1420),
                    isGameBoosted = true,
                    gameName = json.optString("game_name", "Low-Latency Game Boost"),
                    estimatedPingMs = json.optInt("estimated_ping_ms", 15)
                )
                Result.success(resp)
            } else {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Result.failure(Exception("Game boost error ($responseCode): $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed calling game-boost/optimize: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Cleanly removes peer from WireGuard server upon disconnect.
     */
    suspend fun disconnectTunnel(
        context: Context,
        nodeId: String,
        clientPublicKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (clientPublicKey.isEmpty()) return@withContext true
        try {
            val endpoint = "${getBaseUrl(context)}/api/v1/disconnect"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", getApiKey(context))
            }

            val jsonBody = JSONObject().apply {
                put("node_id", nodeId)
                put("client_public_key", clientPublicKey)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.d(TAG, "Disconnect notify error (non-fatal): ${e.message}")
            false
        }
    }

    /**
     * Checks backend health and node count.
     */
    suspend fun checkHealth(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl(context)}/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetches live WireGuard nodes from the backend server with X-API-Key header.
     */
    suspend fun getNodes(context: Context): Result<List<IpNode>> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl(context)}/api/v1/nodes")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("X-API-Key", getApiKey(context))
            }
            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val nodesArray = json.optJSONArray("nodes") ?: JSONArray()
                val list = mutableListOf<IpNode>()
                for (i in 0 until nodesArray.length()) {
                    val item = nodesArray.getJSONObject(i)
                    list.add(
                        IpNode(
                            id = item.optString("id", "us_central_gcp"),
                            name = "${item.optString("country_name", "United States")} (${item.optString("city", "US Central")})",
                            country = item.optString("country_name", "United States"),
                            countryCode = item.optString("country", "US"),
                            flagEmoji = if (item.optString("country") == "US") "🇺🇸" else "🌐",
                            city = item.optString("city", "Central"),
                            latitude = 41.2619,
                            longitude = -95.8608,
                            virtualIp = item.optString("endpoint", DEFAULT_SERVER_HOST).substringBefore(":"),
                            pingMs = 15
                        )
                    )
                }
                Result.success(if (list.isNotEmpty()) list else IpManager.GLOBAL_PRIVACY_NODES)
            } else {
                Result.success(IpManager.GLOBAL_PRIVACY_NODES)
            }
        } catch (e: Exception) {
            Result.success(IpManager.GLOBAL_PRIVACY_NODES)
        }
    }
}
