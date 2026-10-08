package com.fakegps.mocklocation.vpn

import android.content.Context
import android.util.Log

/**
 * WireGuardTunnelManager stub.
 * WireGuard native binary tunnel has been retired to comply with Google Play VpnService policy
 * and 16 KB memory page size requirements.
 */
object WireGuardTunnelManager {

    private const val TAG = "WireGuardTunnelMgr"

    fun getClientPublicKeyBase64(): String = ""

    fun getClientPrivateKeyBase64(): String = ""

    suspend fun startTunnel(
        context: Context,
        serverEndpoint: String,
        serverPublicKey: String,
        assignedClientIp: String,
        dnsServer: String = "1.1.1.1",
        mtu: Int = 1420
    ): Result<Unit> {
        Log.i(TAG, "WireGuard tunnel is disabled to comply with Google Play VpnService policy.")
        return Result.failure(IllegalStateException("VPN service is not permitted for mock location apps."))
    }

    suspend fun verifyHandshake(context: Context, maxWaitMs: Long = 3000L): Boolean = false

    fun stopTunnelSync(context: Context) {}

    fun getStatistics(context: Context): Any? = null

    suspend fun stopTunnel(context: Context): Result<Unit> = Result.success(Unit)

    fun isTunnelActive(context: Context): Boolean = false
}
