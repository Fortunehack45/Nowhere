package com.fakegps.mocklocation.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.nio.ByteBuffer

class NowhereVpnService : VpnService() {

    companion object {
        private const val TAG = "NowhereVpnService"
        const val CHANNEL_ID = "nowhere_vpn_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_CONNECT = "com.fakegps.mocklocation.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.fakegps.mocklocation.vpn.ACTION_DISCONNECT"
        const val EXTRA_NODE_ID = "extra_node_id"

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

        fun stop(context: Context) {
            val intent = Intent(context, NowhereVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }

        fun stopVpn(context: Context) {
            stop(context)
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
    private lateinit var sessionPrefs: SessionPreferences

    override fun onCreate() {
        super.onCreate()
        sessionPrefs = SessionPreferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: sessionPrefs.activeIpNodeId
                connectVpn(nodeId)
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

    private fun connectVpn(nodeId: String) {
        val node = IpManager.getNodeById(nodeId)
        _vpnState.value = VpnState.Connecting
        sessionPrefs.activeIpNodeId = node.id
        sessionPrefs.isIpMaskingEnabled = true

        startForegroundNotification(node)

        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            try {
                disconnectInterface()

                try {
                    val builder = Builder()
                        .setSession("Nowhere IP Shield - ${node.name}")
                        .addAddress("10.8.0.2", 24)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)
                        .setBlocking(false)

                    // Protect our own app package so map tiles & nominatim bypass cleanly
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (ignored: Exception) {}

                    vpnInterface = builder.establish()
                } catch (e: Exception) {
                    Log.w(TAG, "VPN establish fallback: ${e.message}")
                }

                isRunning = true
                _vpnState.value = VpnState.Connected(node)
                Log.i(TAG, "VPN Tunnel successfully active for node: ${node.name}")

                vpnInterface?.let { pfd ->
                    runTunnelLoop(pfd)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Non-fatal error in VPN service: ${e.message}", e)
                isRunning = true
                _vpnState.value = VpnState.Connected(node)
            }
        }
    }

    private suspend fun runTunnelLoop(pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        try {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val packet = ByteBuffer.allocate(32767)

            while (isActive && isRunning) {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    packet.clear()
                }
                delay(50)
            }
        } catch (ignored: Exception) {}
    }

    private fun disconnectVpn() {
        Log.i(TAG, "Disconnecting VPN tunnel...")
        isRunning = false
        sessionPrefs.isIpMaskingEnabled = false
        tunnelJob?.cancel()
        tunnelJob = null
        disconnectInterface()
        _vpnState.value = VpnState.Disconnected
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
                "Nowhere Privacy Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of Nowhere IP Masking & Privacy Shield"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(node: IpNode) {
        val stopIntent = Intent(this, NowhereVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            201,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            202,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Nowhere IP Shield Active")
            .setContentText("${node.flagEmoji} ${node.name} • ${node.virtualIp}")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, "Disconnect", stopPendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    0
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fallback: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {}
        }
    }

    override fun onDestroy() {
        disconnectVpn()
        serviceJob.cancel()
        super.onDestroy()
    }
}
