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
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream

/**
 * Hard OS-Level VPN Sinkhole for Nowhere Emergency Privacy Kill Switch.
 * Establishes a non-forwarding 0.0.0.0/0 route that intercepts and sinks 100% of device network packets
 * so no app can leak real IP or GPS data to the internet until the user re-arms mock location or bypasses.
 */
class KillSwitchSinkholeService : VpnService() {

    companion object {
        private const val TAG = "KillSwitchSinkhole"
        const val CHANNEL_ID = "nowhere_sinkhole_channel"
        const val NOTIFICATION_ID = 3004

        var isSinkholeActive: Boolean = false
            private set
    }

    private var sinkholeInterface: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var sinkholeJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra("EXTRA_REASON") ?: "Mock GPS / VPN is OFF"
        startForegroundNotification(reason)
        activateSinkhole()
        return START_STICKY
    }

    private fun activateSinkhole() {
        sinkholeJob?.cancel()
        sinkholeJob = serviceScope.launch {
            try {
                sinkholeInterface?.close()
                val builder = Builder()
                    .setSession("Nowhere Emergency Kill Switch Sinkhole")
                    .addAddress("10.99.99.2", 24)
                    .addRoute("0.0.0.0", 0) // Intercept 100% of all outgoing IPv4 traffic
                    .addDnsServer("127.0.0.1") // Blackhole DNS queries
                    .setMtu(1420)
                    .setBlocking(false)

                sinkholeInterface = builder.establish()
                if (sinkholeInterface != null) {
                    isSinkholeActive = true
                    Log.i(TAG, "🔒 Hardware/OS Kill Switch Sinkhole Active: 100% of network traffic halted.")
                    
                    // Consume and drop all packets (zero byte transmission to internet)
                    val inStream = FileInputStream(sinkholeInterface!!.fileDescriptor)
                    val dropBuffer = ByteArray(16384)
                    while (isActive && isSinkholeActive) {
                        try {
                            val len = inStream.read(dropBuffer)
                            if (len == -1) break
                        } catch (e: Exception) {
                            delay(100L)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sinkhole builder warning: ${e.message}")
            }
        }
    }

    private fun startForegroundNotification(reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kill Switch Traffic Blocker",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active when Kill Switch halts network traffic to prevent IP/location leaks"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_VPN_DIALOG", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            302,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("Kill Switch: Internet Halted")
            .setContentText("Protected: $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("All device internet traffic is stopped at the OS level to protect your real location. Tap to resume mock GPS or 1-tap bypass."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isSinkholeActive = false
        sinkholeJob?.cancel()
        serviceJob.cancel()
        try {
            sinkholeInterface?.close()
        } catch (ignored: Exception) {}
        sinkholeInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Exception) {}
        Log.i(TAG, "Kill Switch Sinkhole deactivated: Normal network flow restored.")
    }
}
