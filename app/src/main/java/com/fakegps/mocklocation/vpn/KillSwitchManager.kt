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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream

object KillSwitchManager {

    private const val TAG = "KillSwitchManager"
    const val CHANNEL_ID = "nowhere_kill_switch_channel"
    const val NOTIFICATION_ID = 3003

    sealed class KillSwitchStatus {
        object Disabled : KillSwitchStatus()
        object Armed : KillSwitchStatus()
        data class Triggered(val reason: String) : KillSwitchStatus()
        object Bypassed : KillSwitchStatus()
    }

    private val _status = MutableStateFlow<KillSwitchStatus>(KillSwitchStatus.Disabled)
    val status: StateFlow<KillSwitchStatus> = _status.asStateFlow()

    fun evaluate(context: Context) {
        val prefs = SessionPreferences(context)
        if (!prefs.isKillSwitchEnabled) {
            _status.value = KillSwitchStatus.Disabled
            cancelNotification(context)
            stopSinkhole(context)
            return
        }

        if (prefs.isKillSwitchBypassed) {
            _status.value = KillSwitchStatus.Bypassed
            cancelNotification(context)
            stopSinkhole(context)
            return
        }

        val isMockLocationActive = prefs.isSessionActive
        val isVpnActive = NowhereVpnService.isRunning

        if (isMockLocationActive && isVpnActive) {
            _status.value = KillSwitchStatus.Armed
            cancelNotification(context)
            stopSinkhole(context)
        } else {
            val reason = when {
                !isMockLocationActive && !isVpnActive -> "GPS Mocking and VPN Shield are both OFF"
                !isMockLocationActive -> "GPS Mocking session stopped"
                else -> "VPN Privacy Shield disconnected"
            }
            Log.w(TAG, "⚡ Emergency Kill Switch Triggered! Network Sinkhole Activated: $reason")
            _status.value = KillSwitchStatus.Triggered(reason)
            showKillSwitchNotification(context, reason)
            startSinkhole(context, reason)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = SessionPreferences(context)
        prefs.isKillSwitchEnabled = enabled
        if (!enabled) {
            prefs.isKillSwitchBypassed = false
        }
        evaluate(context)
    }

    fun setBypassed(context: Context, bypassed: Boolean) {
        val prefs = SessionPreferences(context)
        prefs.isKillSwitchBypassed = bypassed
        evaluate(context)
    }

    private fun startSinkhole(context: Context, reason: String) {
        try {
            val intent = Intent(context, KillSwitchSinkholeService::class.java).apply {
                putExtra("EXTRA_REASON", reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start KillSwitchSinkholeService: ${e.message}")
        }
    }

    private fun stopSinkhole(context: Context) {
        try {
            val intent = Intent(context, KillSwitchSinkholeService::class.java)
            context.stopService(intent)
        } catch (ignored: Exception) {}
    }

    private fun showKillSwitchNotification(context: Context, reason: String) {
        createNotificationChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_VPN_DIALOG", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            301,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("Privacy Kill Switch Active")
            .setContentText("Internet paused to protect real IP & GPS: $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Internet traffic is physically halted at the OS level because $reason. Tap to resume mock protection or bypass the kill switch."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Privacy Kill Switch Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when Kill Switch halts network traffic to prevent real location/IP leaks"
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
