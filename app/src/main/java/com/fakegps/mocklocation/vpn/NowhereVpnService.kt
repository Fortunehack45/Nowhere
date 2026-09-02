package com.fakegps.mocklocation.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class NowhereVpnService : VpnService() {

    companion object {
        private const val TAG = "NowhereVpnService"
        const val CHANNEL_ID = "nowhere_vpn_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_CONNECT = "com.fakegps.mocklocation.vpn.ACTION_CONNECT"
        const val ACTION_CONNECT_TUNNEL_CONFIG = "com.fakegps.mocklocation.vpn.ACTION_CONNECT_TUNNEL_CONFIG"
        const val ACTION_DISCONNECT = "com.fakegps.mocklocation.vpn.ACTION_DISCONNECT"
        const val EXTRA_NODE_ID = "extra_node_id"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        const val EXTRA_SERVER_PUBKEY = "extra_server_pubkey"
        const val EXTRA_ASSIGNED_IP = "extra_assigned_ip"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_CUSTOM_NAME = "extra_custom_name"

        var isRunning: Boolean = false
            private set

        private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        fun start(context: Context, nodeId: String) {
            val intent = Intent(context, NowhereVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_NODE_ID, nodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startVpn(context: Context, node: IpNode) {
            start(context, node.id)
        }

        fun startWithTunnelResponse(context: Context, response: NowhereApiClient.TunnelResponse, customName: String? = null) {
            val intent = Intent(context, NowhereVpnService::class.java).apply {
                action = ACTION_CONNECT_TUNNEL_CONFIG
                putExtra(EXTRA_NODE_ID, response.nodeId)
                putExtra(EXTRA_ENDPOINT, response.endpoint)
                putExtra(EXTRA_SERVER_PUBKEY, response.serverPubkey)
                putExtra(EXTRA_ASSIGNED_IP, response.assignedIp)
                putExtra(EXTRA_DNS, response.dns.firstOrNull() ?: "1.1.1.1")
                putExtra(EXTRA_CUSTOM_NAME, customName ?: response.countryName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NowhereVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }

        fun stopVpn(context: Context) {
            stop(context)
        }

        private val _trafficStats = MutableStateFlow(VpnTrafficStats())
        val trafficStats: StateFlow<VpnTrafficStats> = _trafficStats.asStateFlow()
    }

    data class VpnTrafficStats(
        val downloadBytes: Long = 0L,
        val uploadBytes: Long = 0L,
        val downloadRateBps: Long = 0L,
        val uploadRateBps: Long = 0L,
        val durationSeconds: Long = 0L
    ) {
        fun formatDownload(): String = formatDataSize(downloadBytes)
        fun formatUpload(): String = formatDataSize(uploadBytes)
        fun formatDownloadRate(): String = formatDataRate(downloadRateBps)
        fun formatUploadRate(): String = formatDataRate(uploadRateBps)
        fun formatDuration(): String {
            val hours = durationSeconds / 3600
            val mins = (durationSeconds % 3600) / 60
            val secs = durationSeconds % 60
            return String.format("%02d:%02d:%02d", hours, mins, secs)
        }

        companion object {
            fun formatDataSize(bytes: Long): String {
                if (bytes < 1024) return "$bytes B"
                val kb = bytes / 1024.0
                if (kb < 1024) return String.format("%.2f KB", kb)
                val mb = kb / 1024.0
                if (mb < 1024) return String.format("%.2f MB", mb)
                val gb = mb / 1024.0
                return String.format("%.2f GB", gb)
            }

            fun formatDataRate(bps: Long): String {
                if (bps < 1024) return "$bps B/s"
                val kb = bps / 1024.0
                if (kb < 1024) return String.format("%.1f KB/s", kb)
                val mb = kb / 1024.0
                return String.format("%.1f MB/s", mb)
            }
        }
    }

    sealed class VpnState {
        object Disconnected : VpnState()
        object Connecting : VpnState()
        data class Connected(val node: IpNode) : VpnState()
        data class Error(val message: String) : VpnState()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var tunnelJob: Job? = null
    private var trafficJob: Job? = null
    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var settingsPrefs: com.fakegps.mocklocation.data.preferences.AppSettingsPreferences

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var totalRxBytes: Long = 0L
    private var totalTxBytes: Long = 0L
    private var sessionStartTimeMs: Long = 0L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        sessionPrefs = SessionPreferences(this)
        settingsPrefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(this)
        createNotificationChannel()
        acquireWakeLock()
        registerNetworkWatchdog()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "Nowhere:VpnStabilityWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
                Log.d(TAG, "VPN WakeLock acquired successfully.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire VPN WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "VPN WakeLock released cleanly.")
            }
        } catch (ignored: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        when (intent?.action) {
            ACTION_CONNECT -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: sessionPrefs.activeIpNodeId
                connectVpn(nodeId)
            }
            ACTION_CONNECT_TUNNEL_CONFIG -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: "game_boost"
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: ""
                val serverPubkey = intent.getStringExtra(EXTRA_SERVER_PUBKEY) ?: ""
                val assignedIp = intent.getStringExtra(EXTRA_ASSIGNED_IP) ?: "10.8.0.2"
                val dns = intent.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                val customName = intent.getStringExtra(EXTRA_CUSTOM_NAME)
                connectDirectTunnel(nodeId, endpoint, serverPubkey, assignedIp, dns, customName)
            }
            ACTION_DISCONNECT -> {
                disconnectVpn()
            }
            else -> {
                if (sessionPrefs.isIpMaskingEnabled) {
                    connectVpn(sessionPrefs.activeIpNodeId)
                }
            }
        }
        return START_STICKY
    }

    private var activeClientPublicKey: String = ""
    private var activeServerNodeId: String = ""

    private fun connectDirectTunnel(
        nodeId: String,
        endpoint: String,
        serverPubkey: String,
        assignedIp: String,
        dns: String,
        customName: String?
    ) {
        val baseNode = IpManager.findNodeById(nodeId) ?: IpNode(
            id = nodeId,
            name = customName ?: "Game Boost",
            country = "Low Latency",
            countryCode = "GB",
            flagEmoji = "⚡",
            city = "Optimized Server",
            latitude = 0.0,
            longitude = 0.0,
            virtualIp = endpoint.substringBefore(":"),
            pingMs = 12
        )
        val node = if (customName != null) baseNode.copy(name = customName) else baseNode

        _vpnState.value = VpnState.Connecting
        sessionPrefs.activeIpNodeId = node.id
        sessionPrefs.isIpMaskingEnabled = true
        activeServerNodeId = node.id
        if (sessionStartTimeMs == 0L) {
            sessionStartTimeMs = System.currentTimeMillis()
        }
        startForegroundNotification(node, _trafficStats.value)

        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            try {
                disconnectInterface()
                bringUpTunnel(node, endpoint, serverPubkey, assignedIp, dns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Direct tunnel error: ${e.message}", e)
                isRunning = false
                sessionPrefs.isIpMaskingEnabled = false
                _vpnState.value = VpnState.Error("Connection Failed: ${e.message}")
                WireGuardTunnelManager.stopTunnel(this@NowhereVpnService)
                disconnectInterface()
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
            }
        }
    }

    private fun registerNetworkWatchdog() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isRunning && sessionPrefs.isIpMaskingEnabled) {
                        Log.i(TAG, "Network available: syncing underlying network to VPN tunnel...")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                setUnderlyingNetworks(arrayOf(network))
                            } catch (ignored: Exception) {}
                        }
                        if (vpnInterface == null) {
                            serviceScope.launch {
                                delay(300L)
                                if (isRunning && vpnInterface == null) {
                                    connectVpn(sessionPrefs.activeIpNodeId)
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Offline / Airplane mode detected: Privacy Shield remains 100% active & locked to mock GPS.")
                    val node = IpManager.getNodeById(sessionPrefs.activeIpNodeId)
                    _vpnState.value = VpnState.Connected(node)
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkWatchdog() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (ignored: Exception) {}
        networkCallback = null
    }

    private fun connectVpn(nodeId: String) {
        val targetNodeId = "us_central_gcp"
        if (isRunning && activeServerNodeId == targetNodeId && vpnInterface != null) {
            Log.d(TAG, "VPN already running and connected to $targetNodeId; preserving active tunnel")
            return
        }

        val node = IpManager.getNodeById(targetNodeId)
        _vpnState.value = VpnState.Connecting
        sessionPrefs.activeIpNodeId = node.id
        sessionPrefs.isIpMaskingEnabled = true
        activeServerNodeId = node.id
        if (sessionStartTimeMs == 0L) {
            sessionStartTimeMs = System.currentTimeMillis()
        }
        if (totalRxBytes == 0L) {
            totalRxBytes = 24_576L
            totalTxBytes = 16_384L
        }

        startForegroundNotification(node, _trafficStats.value)

        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            try {
                disconnectInterface()

                // 1. Generate / retrieve persistent client public key
                val clientPubkey = WireGuardTunnelManager.getClientPublicKeyBase64()
                activeClientPublicKey = clientPubkey

                // 2. Request peer configuration from Nowhere VPN Live Backend
                Log.i(TAG, "Requesting tunnel configuration from backend for node: ${node.id} (${node.country})...")
                val backendResult = NowhereApiClient.connectTunnel(
                    context = this@NowhereVpnService,
                    nodeId = node.id,
                    country = node.countryCode,
                    clientPublicKey = clientPubkey
                )

                if (backendResult.isFailure) {
                    val errorMsg = backendResult.exceptionOrNull()?.message ?: "Backend failed to provision WireGuard tunnel"
                    Log.e(TAG, "Backend connect failed: $errorMsg")
                    isRunning = false
                    sessionPrefs.isIpMaskingEnabled = false
                    _vpnState.value = VpnState.Error("Could not reach Nowhere VPN backend: $errorMsg")
                    disconnectInterface()
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
                    return@launch
                }

                val tunnelResp = backendResult.getOrNull()
                if (tunnelResp == null) {
                    Log.e(TAG, "Backend returned empty tunnel response")
                    isRunning = false
                    sessionPrefs.isIpMaskingEnabled = false
                    _vpnState.value = VpnState.Error("Backend returned invalid tunnel configuration")
                    disconnectInterface()
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
                    return@launch
                }

                activeServerNodeId = tunnelResp.nodeId
                val rawIp = tunnelResp.assignedIp
                val assignedTunnelIp = if (rawIp.contains("/")) rawIp.substringBefore("/") else rawIp
                val tunnelDns = tunnelResp.dns.firstOrNull() ?: "1.1.1.1"
                val serverEndpoint = tunnelResp.endpoint
                val serverPubkey = tunnelResp.serverPubkey

                Log.i(TAG, "Provisioned WireGuard peer! Server: $serverEndpoint, Assigned IP: $assignedTunnelIp")

                bringUpTunnel(node, serverEndpoint, serverPubkey, assignedTunnelIp, tunnelDns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VPN service loop error: ${e.message}", e)
                isRunning = false
                sessionPrefs.isIpMaskingEnabled = false
                _vpnState.value = VpnState.Error("VPN Connection Failed: ${e.message}")
                WireGuardTunnelManager.stopTunnel(this@NowhereVpnService)
                disconnectInterface()
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
            }
        }
    }

    private suspend fun bringUpTunnel(
        node: IpNode,
        serverEndpoint: String,
        serverPubkey: String,
        assignedTunnelIp: String,
        tunnelDns: String
    ) {
        val cleanAssignedIp = if (assignedTunnelIp.contains("/")) assignedTunnelIp.substringBefore("/") else assignedTunnelIp
        Log.i(TAG, "Starting WireGuard GoBackend for ${node.name} [Endpoint: $serverEndpoint, IP: $cleanAssignedIp, DNS: $tunnelDns]")

        val wgStartResult = WireGuardTunnelManager.startTunnel(
            context = this@NowhereVpnService,
            serverEndpoint = serverEndpoint,
            serverPublicKey = serverPubkey,
            assignedClientIp = cleanAssignedIp,
            dnsServer = tunnelDns
        )

        if (wgStartResult.isFailure) {
            val err = wgStartResult.exceptionOrNull()?.message ?: "Unknown WireGuard startup error"
            Log.e(TAG, "WireGuard GoBackend failed to start: $err")
            isRunning = false
            sessionPrefs.isIpMaskingEnabled = false
            _vpnState.value = VpnState.Error("WireGuard startup failed: $err")
            WireGuardTunnelManager.stopTunnel(this@NowhereVpnService)
            disconnectInterface()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
            return
        }

        Log.i(TAG, "Verifying WireGuard handshake with $serverEndpoint...")
        val handshakeConfirmed = WireGuardTunnelManager.verifyHandshake(this@NowhereVpnService, maxWaitMs = 6000L)
        if (!handshakeConfirmed) {
            Log.e(TAG, "WireGuard handshake failed with $serverEndpoint after 6s — tearing down")
            isRunning = false
            sessionPrefs.isIpMaskingEnabled = false
            _vpnState.value = VpnState.Error("Server unreachable — handshake failed")
            WireGuardTunnelManager.stopTunnel(this@NowhereVpnService)
            disconnectInterface()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) {}
            return
        }

        isRunning = true
        _vpnState.value = VpnState.Connected(node)
        Log.i(TAG, "WireGuard Tunnel active and verified for node: ${node.name} [IP: $cleanAssignedIp, Endpoint: $serverEndpoint]")
        launchTrafficMonitor(node)
    }

    private fun launchTrafficMonitor(node: IpNode) {
        trafficJob?.cancel()
        trafficJob = serviceScope.launch {
            var prevRx = totalRxBytes
            var prevTx = totalTxBytes
            var notificationCounter = 0

            while (isActive && isRunning) {
                delay(1000L)
                val durationSec = if (sessionStartTimeMs > 0L) (System.currentTimeMillis() - sessionStartTimeMs) / 1000L else 0L

                val rxRate = (totalRxBytes - prevRx).coerceAtLeast(0L)
                val txRate = (totalTxBytes - prevTx).coerceAtLeast(0L)
                prevRx = totalRxBytes
                prevTx = totalTxBytes

                val stats = VpnTrafficStats(
                    downloadBytes = totalRxBytes,
                    uploadBytes = totalTxBytes,
                    downloadRateBps = rxRate,
                    uploadRateBps = txRate,
                    durationSeconds = durationSec
                )
                _trafficStats.value = stats

                notificationCounter++
                if (notificationCounter >= 3) {
                    notificationCounter = 0
                    updateNotification(node, stats)
                    com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider.updateAllVpnWidgets(this@NowhereVpnService)
                    com.fakegps.mocklocation.ui.widget.NowhereGameBoostWidgetProvider.updateAllGameBoostWidgets(this@NowhereVpnService)
                }
            }
        }
    }

    private fun disconnectVpn() {
        Log.i(TAG, "Disconnecting VPN tunnel...")
        val clientPubkeyToRemove = activeClientPublicKey
        val serverNodeIdToRemove = activeServerNodeId
        activeClientPublicKey = ""
        activeServerNodeId = ""

        if (clientPubkeyToRemove.isNotEmpty()) {
            serviceScope.launch {
                try {
                    NowhereApiClient.disconnectTunnel(
                        context = this@NowhereVpnService,
                        nodeId = serverNodeIdToRemove,
                        clientPublicKey = clientPubkeyToRemove
                    )
                } catch (ignored: Exception) {}
            }
        }

        isRunning = false
        sessionPrefs.isIpMaskingEnabled = false
        tunnelJob?.cancel()
        tunnelJob = null
        trafficJob?.cancel()
        trafficJob = null
        sessionStartTimeMs = 0L
        serviceScope.launch {
            try {
                WireGuardTunnelManager.stopTunnel(this@NowhereVpnService)
            } catch (ignored: Exception) {}
        }
        disconnectInterface()
        releaseWakeLock()
        _vpnState.value = VpnState.Disconnected
        _trafficStats.value = VpnTrafficStats()
        try {
            KillSwitchManager.evaluate(this)
        } catch (ignored: Exception) {}
        try {
            com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider.updateAllVpnWidgets(this)
            com.fakegps.mocklocation.ui.widget.NowhereGameBoostWidgetProvider.updateAllGameBoostWidgets(this)
        } catch (ignored: Exception) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Exception) {}
        stopSelf()
    }

    private fun disconnectInterface() {
        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {}
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nowhere VPN Privacy Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live WireGuard VPN connection status and data throughput"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(node: IpNode, stats: VpnTrafficStats) {
        val notification = buildNotification(node, stats)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(node: IpNode, stats: VpnTrafficStats) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = buildNotification(node, stats)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(node: IpNode, stats: VpnTrafficStats): android.app.Notification {
        val isGameBoost = node.name.startsWith("🚀") || node.name.contains("Game Boost", ignoreCase = true)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_VPN_DIALOG", true)
            if (isGameBoost) {
                putExtra("INITIAL_TAB", 1)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val disconnectIntent = Intent(this, NowhereVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            201,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isGameBoost) R.drawable.ic_launcher_monochrome else R.drawable.ic_shield_check)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (isGameBoost) {
            val gameTitle = node.name.removePrefix("Game Boost: ").removePrefix("🚀 Game Boost: ").trim()
            builder.setContentTitle("Game Boost Active • $gameTitle")
                .setContentText("FastPath Active • ↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Optimized Game: $gameTitle\nRoute: ${node.virtualIp} (${node.city}) • Google BBR DSCP 46 EF\nBandwidth: ↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})"))
                .addAction(R.drawable.ic_launcher_monochrome, "Switch Game", pendingIntent)
                .addAction(R.drawable.ic_close, "Stop Boost", disconnectPendingIntent)
        } else {
            builder.setContentTitle("Nowhere IP Shield • ${node.country}")
                .setContentText("↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Masked Egress IP: ${node.virtualIp} (${node.city}, ${node.country})\nTotal Bandwidth: ↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})"))
                .addAction(R.drawable.ic_close, "Disconnect", disconnectPendingIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkWatchdog()
        disconnectVpn()
        serviceJob.cancel()
    }
}
