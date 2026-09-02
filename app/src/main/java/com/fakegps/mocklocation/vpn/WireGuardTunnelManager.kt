package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
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

object WireGuardTunnelManager {

    private const val TAG = "WireGuardTunnelMgr"

    private var backend: Backend? = null
    private var activeTunnel: NowhereTunnel? = null
    private var localKeyPair: KeyPair? = null

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
    fun getOrCreateKeyPair(): KeyPair {
        if (localKeyPair == null) {
            localKeyPair = KeyPair()
        }
        return localKeyPair!!
    }

    fun getClientPublicKeyBase64(): String {
        return getOrCreateKeyPair().publicKey.toBase64()
    }

    fun getClientPrivateKeyBase64(): String {
        return getOrCreateKeyPair().privateKey.toBase64()
    }

    @Synchronized
    private fun getBackend(context: Context): Backend {
        if (backend == null) {
            backend = GoBackend(context.applicationContext)
        }
        return backend!!
    }

    /**
     * Brings up a real WireGuard VPN tunnel to the specified node endpoint.
     */
    suspend fun startTunnel(
        context: Context,
        serverEndpoint: String,
        serverPublicKey: String,
        assignedClientIp: String,
        dnsServer: String = "1.1.1.1",
        mtu: Int = 1420
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wgBackend = getBackend(context)
            val tunnel = activeTunnel ?: NowhereTunnel().also { activeTunnel = it }

            // Ensure clean state
            try {
                if (wgBackend.getState(tunnel) == Tunnel.State.UP) {
                    wgBackend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (ignored: Exception) {}

            val clientPrivKey = getClientPrivateKeyBase64()
            val cleanAssignedIp = if (assignedClientIp.contains("/")) assignedClientIp else "$assignedClientIp/32"

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(clientPrivKey)
                .addAddress(InetNetwork.parse(cleanAssignedIp))

            try {
                ifaceBuilder.addDnsServer(InetAddress.getByName(dnsServer))
            } catch (ignored: Exception) {
                ifaceBuilder.addDnsServer(InetAddress.getByName("1.1.1.1"))
            }

            val peerBuilder = Peer.Builder()
                .parsePublicKey(serverPublicKey)
                .setEndpoint(InetEndpoint.parse(serverEndpoint))
                .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))

            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            Log.i(TAG, "Starting WireGuard GoBackend with endpoint $serverEndpoint and assigned IP $cleanAssignedIp")
            wgBackend.setState(tunnel, Tunnel.State.UP, config)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting WireGuard tunnel: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Tears down the active WireGuard tunnel.
     */
    suspend fun stopTunnel(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val wgBackend = getBackend(context)
            val tunnel = activeTunnel
            if (tunnel != null) {
                wgBackend.setState(tunnel, Tunnel.State.DOWN, null)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WireGuard tunnel: ${e.message}")
            Result.failure(e)
        }
    }

    fun isTunnelActive(context: Context): Boolean {
        return try {
            val tunnel = activeTunnel ?: return false
            getBackend(context).getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }
}
