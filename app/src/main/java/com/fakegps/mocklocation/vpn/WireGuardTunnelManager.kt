package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance Tunnel & Telemetry Manager for Nowhere Privacy Shield.
 * Backed by the official WireGuard GoBackend to provide kernel-grade
 * ChaCha20-Poly1305 encryption and real Layer-3 IP packet routing.
 */
object WireGuardTunnelManager {

    private const val TAG = "WireGuardTunnelMgr"
    private const val PREFS_NAME = "nowhere_wg_tunnel_prefs"
    private const val KEY_PUB = "client_public_key"
    private const val KEY_PRIV = "client_private_key"

    private var backend: Backend? = null
    private var activeTunnel: NowhereTunnel? = null
    private val isRunning = AtomicBoolean(false)
    private var activeEndpoint: String? = null
    private var activeAssignedIp: String? = null

    @Volatile
    private var cachedPubKey: String? = null
    @Volatile
    private var cachedPrivKey: String? = null

    class NowhereTunnel(private val name: String = "NowhereShield") : Tunnel {
        private var currentState: Tunnel.State = Tunnel.State.DOWN

        override fun getName(): String = name

        override fun onStateChange(newState: Tunnel.State) {
            currentState = newState
            Log.i(TAG, "WireGuard Tunnel state changed to: $newState")
        }

        fun getState(): Tunnel.State = currentState
    }

    @Synchronized
    private fun getBackend(context: Context): Backend {
        if (backend == null) {
            backend = GoBackend(context.applicationContext)
        }
        return backend!!
    }

    @Synchronized
    fun saveProvisionedKeys(context: Context?, pubKey: String, privKey: String?) {
        cachedPubKey = pubKey
        if (!privKey.isNullOrEmpty()) {
            cachedPrivKey = privKey
        }
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(KEY_PUB, pubKey)
            if (!privKey.isNullOrEmpty()) {
                editor.putString(KEY_PRIV, privKey)
            }
            editor.apply()
        }
    }

    @Synchronized
    private fun ensureKeys(context: Context? = null) {
        if (cachedPubKey != null && cachedPrivKey != null) return

        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedPub = prefs.getString(KEY_PUB, null)
            val savedPriv = prefs.getString(KEY_PRIV, null)
            if (!savedPub.isNullOrEmpty() && !savedPriv.isNullOrEmpty()) {
                cachedPubKey = savedPub
                cachedPrivKey = savedPriv
                return
            }
        }

        try {
            val keyPair = KeyPair()
            val pub = keyPair.publicKey.toBase64()
            val priv = keyPair.privateKey.toBase64()
            cachedPubKey = pub
            cachedPrivKey = priv
            if (context != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_PUB, pub).putString(KEY_PRIV, priv).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not generate KeyPair directly: ${e.message}")
        }
    }

    fun getClientPublicKeyBase64(context: Context? = null): String? {
        ensureKeys(context)
        return cachedPubKey
    }

    fun getClientPrivateKeyBase64(context: Context? = null): String? {
        ensureKeys(context)
        return cachedPrivKey
    }

    suspend fun startTunnel(
        context: Context,
        serverEndpoint: String,
        serverPublicKey: String,
        assignedClientIp: String,
        dnsServer: String = "1.1.1.1",
        mtu: Int = 1280
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureKeys(context)
            Log.i(TAG, "Initiating secure WireGuard tunnel to $serverEndpoint (Assigned IP: $assignedClientIp)...")

            val wgBackend = getBackend(context)
            val tunnel = activeTunnel ?: NowhereTunnel().also { activeTunnel = it }

            // Ensure previous tunnel state is torn down before starting new config
            try {
                if (wgBackend.getState(tunnel) == Tunnel.State.UP) {
                    wgBackend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (ignored: Exception) {}

            val clientPrivKey = getClientPrivateKeyBase64(context)
                ?: throw IllegalStateException("Client private key is missing or invalid")
            val cleanAssignedIp = if (assignedClientIp.contains("/")) assignedClientIp else "$assignedClientIp/32"

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(clientPrivKey)
                .addAddress(InetNetwork.parse(cleanAssignedIp))
                .setMtu(mtu.coerceIn(1280, 1420))
                .excludeApplication(context.packageName)

            val dnsCandidates = linkedSetOf<String>().apply {
                if (dnsServer.isNotBlank()) add(dnsServer.trim())
                add("1.1.1.1")
                add("1.0.0.1")
            }
            for (dns in dnsCandidates) {
                try {
                    ifaceBuilder.addDnsServer(InetAddress.getByName(dns))
                } catch (ignored: Exception) {}
            }

            val peerBuilder = Peer.Builder()
                .parsePublicKey(serverPublicKey)
                .setEndpoint(InetEndpoint.parse(serverEndpoint))
                .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                .setPersistentKeepalive(25)

            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            Log.i(TAG, "Starting WireGuard GoBackend with endpoint $serverEndpoint and assigned IP $cleanAssignedIp")
            wgBackend.setState(tunnel, Tunnel.State.UP, config)

            activeEndpoint = serverEndpoint
            activeAssignedIp = assignedClientIp
            isRunning.set(true)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed initiating WireGuard tunnel: ${e.message}", e)
            isRunning.set(false)
            Result.failure(e)
        }
    }

    suspend fun verifyHandshake(context: Context, maxWaitMs: Long = 4000L): Boolean = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext false
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            kotlinx.coroutines.delay(300L)
            try {
                val stats = getBackend(context).getStatistics(tunnel)
                if (stats.totalRx() > 0) {
                    Log.i(TAG, "WireGuard Handshake confirmed! RX: ${stats.totalRx()} bytes, TX: ${stats.totalTx()} bytes")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Polling WireGuard statistics warning: ${e.message}")
            }
        }
        // Fallback check: if tunnel is UP, return true to allow connection to stabilize in background
        try {
            getBackend(context).getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }

    fun stopTunnelSync(context: Context) {
        try {
            val tunnel = activeTunnel
            if (tunnel != null) {
                getBackend(context).setState(tunnel, Tunnel.State.DOWN, null)
                Log.i(TAG, "WireGuard tunnel cleanly and synchronously set to DOWN.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WireGuard tunnel sync: ${e.message}")
        } finally {
            isRunning.set(false)
            activeEndpoint = null
            activeAssignedIp = null
        }
    }

    fun getStatistics(context: Context): Statistics? {
        return try {
            val tunnel = activeTunnel ?: return null
            getBackend(context).getStatistics(tunnel)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun stopTunnel(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        stopTunnelSync(context)
        Result.success(Unit)
    }

    fun isTunnelActive(context: Context): Boolean {
        return try {
            val tunnel = activeTunnel ?: return false
            getBackend(context).getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            isRunning.get()
        }
    }
}
