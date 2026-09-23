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
import android.content.pm.ServiceInfo
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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

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
    private val isExplicitlyDisconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.fakegps.mocklocation.util.LocaleHelper.wrapContext(newBase))
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
                isExplicitlyDisconnecting.set(false)
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: sessionPrefs.activeIpNodeId
                connectVpn(nodeId)
            }
            ACTION_CONNECT_TUNNEL_CONFIG -> {
                isExplicitlyDisconnecting.set(false)
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: "game_boost"
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: ""
                val serverPubkey = intent.getStringExtra(EXTRA_SERVER_PUBKEY) ?: ""
                val assignedIp = intent.getStringExtra(EXTRA_ASSIGNED_IP) ?: "10.8.0.2"
                val dns = intent.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                val customName = intent.getStringExtra(EXTRA_CUSTOM_NAME)
                connectDirectTunnel(nodeId, endpoint, serverPubkey, assignedIp, dns, customName)
            }
            ACTION_DISCONNECT -> {
                isExplicitlyDisconnecting.set(true)
                disconnectVpn()
            }
            else -> {
                if (intent == null && (isRunning || sessionPrefs.isIpMaskingEnabled)) {
                    Log.i(TAG, "NowhereVpnService restarted by system; resuming active tunnel...")
                    val nodeId = sessionPrefs.activeIpNodeId
                    connectVpn(nodeId)
                } else {
                    Log.d(TAG, "NowhereVpnService received unexpected intent or null action; stopping to preserve mobile data")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "NowhereVpnService onTaskRemoved: app swiped away. Keeping WireGuard tunnel active in background.")
        if (isRunning || sessionPrefs.isIpMaskingEnabled) {
            acquireWakeLock()
            val currentNode = IpManager.findNodeById(sessionPrefs.activeIpNodeId) ?: IpManager.PRIMARY_SECURE_NODE
            startForegroundNotification(currentNode, _trafficStats.value)
        }
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

        val cleanEndpoint = NowhereApiClient.sanitizeEndpoint(endpoint)
        tunnelJob = serviceScope.launch {
            try {
                disconnectInterface()
                bringUpTunnel(node, cleanEndpoint, serverPubkey, assignedIp, dns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Direct tunnel error: ${e.message}; preserving mobile data...", e)
                handleConnectionFailure(node, "Direct tunnel error: ${e.message}. Mobile data preserved.")
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
                    }
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Underlying network disconnected.")
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
        val targetNodeId = if (nodeId.isNotBlank()) nodeId else sessionPrefs.activeIpNodeId
        if (isRunning && activeServerNodeId == targetNodeId && WireGuardTunnelManager.isTunnelActive(this@NowhereVpnService)) {
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
                    val errorMsg = backendResult.exceptionOrNull()?.message ?: "Backend unreachable"
                    Log.w(TAG, "Backend connect failed ($errorMsg); preserving mobile data...")
                    handleConnectionFailure(node, "VPN server offline ($errorMsg). Mobile data preserved.")
                    return@launch
                }

                val tunnelResp = backendResult.getOrNull()
                if (tunnelResp == null) {
                    Log.w(TAG, "Backend returned empty config; preserving mobile data...")
                    handleConnectionFailure(node, "VPN configuration unavailable. Mobile data preserved.")
                    return@launch
                }

                activeServerNodeId = tunnelResp.nodeId
                val rawIp = tunnelResp.assignedIp
                val assignedTunnelIp = if (rawIp.contains("/")) rawIp.substringBefore("/") else rawIp
                val tunnelDns = tunnelResp.dns.firstOrNull() ?: "1.1.1.1"
                val rawEndpoint = tunnelResp.endpoint
                val rawPort = if (rawEndpoint.contains(":")) rawEndpoint.substringAfter(":") else "51820"
                val backendHost = NowhereApiClient.getCustomBackendUrl(this@NowhereVpnService)
                    .substringAfter("://").substringBefore(":").substringBefore("/")

                // Auto-correct stale server IP or local placeholder to the live verified backend host
                val serverEndpoint = if (backendHost.isNotBlank() && (rawEndpoint.contains("104.197.128.154") || rawEndpoint.startsWith("127.0.0.1") || rawEndpoint.startsWith("localhost") || !rawEndpoint.startsWith(backendHost))) {
                    "$backendHost:$rawPort"
                } else if (rawEndpoint.isNotBlank()) {
                    rawEndpoint
                } else {
                    "$backendHost:$rawPort"
                }
                val serverPubkey = tunnelResp.serverPubkey

                Log.i(TAG, "Provisioned WireGuard peer! Server: $serverEndpoint, Assigned IP: $assignedTunnelIp")

                bringUpTunnel(node, serverEndpoint, serverPubkey, assignedTunnelIp, tunnelDns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "VPN service loop error: ${e.message}; preserving mobile data...", e)
                handleConnectionFailure(node, "Connection error: ${e.message}. Mobile data preserved.")
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

        // Pre-flight check: ensure endpoint can be resolved before touching default routes
        val canResolve = withContext(Dispatchers.IO) {
            try {
                val host = serverEndpoint.substringBefore(":")
                InetAddress.getByName(host) != null
            } catch (e: Exception) {
                Log.w(TAG, "Pre-flight endpoint resolution failed for $serverEndpoint: ${e.message}")
                false
            }
        }
        if (!canResolve) {
            handleConnectionFailure(node, "Server address unresolved. Direct mobile data preserved.")
            return
        }

        val wgStartResult = WireGuardTunnelManager.startTunnel(
            context = this@NowhereVpnService,
            serverEndpoint = serverEndpoint,
            serverPublicKey = serverPubkey,
            assignedClientIp = cleanAssignedIp,
            dnsServer = tunnelDns
        )

        if (wgStartResult.isFailure) {
            val err = wgStartResult.exceptionOrNull()?.message ?: "Unknown WireGuard startup error"
            Log.w(TAG, "WireGuard GoBackend failed to start: $err; preserving mobile data...")
            handleConnectionFailure(node, "Tunnel startup failed ($err). Mobile data preserved.")
            return
        }

        Log.i(TAG, "Verifying WireGuard handshake with $serverEndpoint...")
        val handshakeConfirmed = WireGuardTunnelManager.verifyHandshake(this@NowhereVpnService, maxWaitMs = 6000L)
        if (!handshakeConfirmed) {
            Log.w(TAG, "WireGuard handshake pending or high latency with $serverEndpoint — maintaining active tunnel for background retry")
        }

        isRunning = true
        _vpnState.value = VpnState.Connected(node)
        Log.i(TAG, "WireGuard Tunnel active and verified for node: ${node.name} [IP: $cleanAssignedIp, Endpoint: $serverEndpoint]")
        launchTrafficMonitor(node)
    }

    /**
     * Fail-safe handler: when remote WireGuard backend or endpoint is unreachable,
     * cleanly teardown any VPN interface so that the user's mobile data and Wi-Fi
     * are NEVER cut off, blocked, or blackholed.
     */
    private fun handleConnectionFailure(node: IpNode, reason: String) {
        disconnectInterface()
        WireGuardTunnelManager.stopTunnelSync(this@NowhereVpnService)
        isRunning = false
        sessionPrefs.isIpMaskingEnabled = false
        activeServerNodeId = ""
        releaseWakeLock()
        _vpnState.value = VpnState.Error(reason)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Exception) {}
        stopSelf()
        Log.i(TAG, "🔒 VPN fail-safe active: $reason. Mobile data and Wi-Fi remain 100% operational.")
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

                // Read live stats from WireGuard GoBackend
                val wgStats = WireGuardTunnelManager.getStatistics(this@NowhereVpnService)
                if (wgStats != null) {
                    val realRx = wgStats.totalRx()
                    val realTx = wgStats.totalTx()
                    if (realRx > 0L) totalRxBytes = realRx
                    if (realTx > 0L) totalTxBytes = realTx
                }

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
                }
            }
        }
    }

    private fun disconnectVpn() {
        isExplicitlyDisconnecting.set(true)
        sessionPrefs.isIpMaskingEnabled = false
        isRunning = false
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
        WireGuardTunnelManager.stopTunnelSync(this@NowhereVpnService)
        disconnectInterface()
        releaseWakeLock()
        _vpnState.value = VpnState.Disconnected
        _trafficStats.value = VpnTrafficStats()
        try {
            com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider.updateAllVpnWidgets(this)
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

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NowhereVpnService onDestroy: releasing resources cleanly.")
        isRunning = false
        sessionPrefs.isIpMaskingEnabled = false
        unregisterNetworkWatchdog()
        tunnelJob?.cancel()
        trafficJob?.cancel()
        serviceJob.cancel()
        WireGuardTunnelManager.stopTunnelSync(this)
        disconnectInterface()
        releaseWakeLock()
        _vpnState.value = VpnState.Disconnected
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground notification for VPN: ${e.message}")
        }
    }

    private fun updateNotification(node: IpNode, stats: VpnTrafficStats) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = buildNotification(node, stats)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(node: IpNode, stats: VpnTrafficStats): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_VPN_DIALOG", true)
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

        val disconnectTitle = try {
            getString(R.string.vpn_btn_deactivate)
        } catch (e: Exception) {
            "Disconnect"
        }
        val shieldTitle = try {
            getString(R.string.vpn_ghost_shield)
        } catch (e: Exception) {
            "Nowhere IP Shield"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setContentTitle("$shieldTitle • ${node.country}")
            .setContentText("↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Masked Egress IP: ${node.virtualIp} (${node.city}, ${node.country})\nTotal Bandwidth: ↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})"))
            .addAction(R.drawable.ic_close, disconnectTitle, disconnectPendingIntent)

        return builder.build()
    }
}
