package com.fakegps.mocklocation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereRouteWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereSessionTimerWidgetProvider
import com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionTimerManager {

    private const val TAG = "SessionTimerManager"
    const val CHANNEL_ID = "nowhere_session_timer_channel"

    const val NOTIF_ID_60S = 3001
    const val NOTIF_ID_30S = 3002
    const val NOTIF_ID_10S = 3003
    const val NOTIF_ID_EXPIRED = 3004

    const val ACTION_EXTEND_ONE_HOUR = "com.fakegps.mocklocation.ACTION_EXTEND_ONE_HOUR"
    const val ACTION_RECONNECT_20M = "com.fakegps.mocklocation.ACTION_RECONNECT_20M"

    data class SessionTimerState(
        val isRunning: Boolean = false,
        val isExpired: Boolean = false,
        val remainingMillis: Long = 0L,
        val totalAllocatedMillis: Long = 0L,
        val formattedRemaining: String = "00:00:00",
        val formattedTotal: String = "2h 00m",
        val progressPercent: Int = 100
    )

    private val _timerState = MutableStateFlow(SessionTimerState())
    val timerState: StateFlow<SessionTimerState> = _timerState.asStateFlow()

    private var timerScope: CoroutineScope? = null
    private var timerJob: Job? = null

    private var hasFired60s = false
    private var hasFired30s = false
    private var hasFired10s = false
    private var hasFiredExpired = false

    fun startOrResumeTimer(context: Context, durationMillis: Long = SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS) {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.isSessionActive = true
        if (sessionPrefs.hasValidActiveSession()) {
            resumeExistingTimer(context)
        } else {
            startTimer(context, durationMillis, forceRestart = false)
        }
    }

    fun startTimer(
        context: Context,
        durationMillis: Long = SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS,
        forceRestart: Boolean = false
    ) {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.startNewSession(durationMillis, forceRestart = forceRestart)
        resetThresholdFlags()

        ensureNotificationChannel(context)
        startTickerLoop(context.applicationContext)
    }

    fun extendSession(context: Context, extraMillis: Long = SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS) {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.extendSession(extraMillis)
        resetThresholdFlags()

        cancelNotification(context, NOTIF_ID_60S)
        cancelNotification(context, NOTIF_ID_30S)
        cancelNotification(context, NOTIF_ID_10S)
        cancelNotification(context, NOTIF_ID_EXPIRED)

        updateState(context)
        NowhereAppWidgetProvider.updateAllWidgets(context)
    }

    fun resumeExistingTimer(context: Context) {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.isSessionActive = true
        sessionPrefs.isSessionExpired = false
        updateState(context)
        ensureNotificationChannel(context)
        startTickerLoop(context.applicationContext)
    }

    fun stopTimer(context: Context) {
        timerJob?.cancel()
        timerJob = null
        timerScope?.cancel()
        timerScope = null

        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.isSessionActive = false
        resetThresholdFlags()

        updateState(context)
        NowhereAppWidgetProvider.updateAllWidgets(context)
    }

    private fun resetThresholdFlags() {
        hasFired60s = false
        hasFired30s = false
        hasFired10s = false
        hasFiredExpired = false
    }

    private fun startTickerLoop(appContext: Context) {
        if (timerJob?.isActive == true) return

        timerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        timerJob = timerScope?.launch {
            while (isActive) {
                val sessionPrefs = SessionPreferences(appContext)
                val remainingMillis = sessionPrefs.getTimeRemainingMillis()
                val totalAllocated = sessionPrefs.sessionAllocatedDurationMillis

                if (!sessionPrefs.isSessionActive) {
                    _timerState.value = SessionTimerState()
                    break
                }

                if (remainingMillis <= 0L) {
                    sessionPrefs.isSessionExpired = true
                    val state = SessionTimerState(
                        isRunning = false,
                        isExpired = true,
                        remainingMillis = 0L,
                        totalAllocatedMillis = totalAllocated,
                        formattedRemaining = "00:00:00",
                        formattedTotal = sessionPrefs.formatAllocatedDuration(),
                        progressPercent = 0
                    )
                    _timerState.value = state

                    if (!hasFiredExpired) {
                        hasFiredExpired = true
                        notifySessionExpired(appContext)
                        // Trigger service expiration pause/stop
                        withContext(Dispatchers.Main) {
                            val stopIntent = Intent(appContext, MockLocationService::class.java).apply {
                                action = MockLocationService.ACTION_STOP
                            }
                            appContext.startService(stopIntent)
                        }
                    }
                    NowhereAppWidgetProvider.updateAllWidgets(appContext)
                    break
                } else {
                    val remainingSecs = remainingMillis / 1000L
                    checkAndNotifyThresholds(appContext, remainingSecs)

                    val percent = if (totalAllocated > 0) {
                        ((remainingMillis.toDouble() / totalAllocated.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else 0

                    _timerState.value = SessionTimerState(
                        isRunning = true,
                        isExpired = false,
                        remainingMillis = remainingMillis,
                        totalAllocatedMillis = totalAllocated,
                        formattedRemaining = sessionPrefs.formatRemainingTime(),
                        formattedTotal = sessionPrefs.formatAllocatedDuration(),
                        progressPercent = percent
                    )

                    // Real-time 1-second direct home screen widget refresh
                    NowhereSessionTimerWidgetProvider.updateAllSessionWidgets(appContext)
                    if (sessionPrefs.activeMode == "ROUTE") {
                        NowhereRouteWidgetProvider.updateAllRouteWidgets(appContext)
                    }
                    if (sessionPrefs.isIpMaskingEnabled) {
                        NowhereVpnWidgetProvider.updateAllVpnWidgets(appContext)
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun updateState(context: Context) {
        val sessionPrefs = SessionPreferences(context)
        val remainingMillis = sessionPrefs.getTimeRemainingMillis()
        val totalAllocated = sessionPrefs.sessionAllocatedDurationMillis
        val percent = if (totalAllocated > 0) {
            ((remainingMillis.toDouble() / totalAllocated.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else 0

        _timerState.value = SessionTimerState(
            isRunning = sessionPrefs.isSessionActive && remainingMillis > 0,
            isExpired = sessionPrefs.isSessionExpired,
            remainingMillis = remainingMillis,
            totalAllocatedMillis = totalAllocated,
            formattedRemaining = sessionPrefs.formatRemainingTime(),
            formattedTotal = sessionPrefs.formatAllocatedDuration(),
            progressPercent = percent
        )
    }

    private fun checkAndNotifyThresholds(context: Context, remainingSecs: Long) {
        if (remainingSecs in 51..60 && !hasFired60s) {
            hasFired60s = true
            notifyThreshold(context, NOTIF_ID_60S, "⚠️ 1 Minute Remaining", "Your mock location simulation expires in 60 seconds! Tap to extend by +2 hours.")
        } else if (remainingSecs in 21..30 && !hasFired30s) {
            hasFired30s = true
            notifyThreshold(context, NOTIF_ID_30S, "⏳ 30 Seconds Remaining", "Simulation will pause in 30 seconds. Watch a short video to add +2 hours.")
        } else if (remainingSecs in 1..10 && !hasFired10s) {
            hasFired10s = true
            notifyThreshold(context, NOTIF_ID_10S, "🚨 10 Seconds Left!", "Simulation is about to expire! Tap here immediately to keep spoofing active.")
        }
    }

    private fun notifyThreshold(context: Context, notifId: Int, title: String, message: String) {
        if (!com.fakegps.mocklocation.util.PermissionHelper.hasNotificationPermission(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SESSION_EXTEND_DIALOG", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_bolt, "Extend +2 Hours", pendingOpen)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notifId, notification)
    }

    private fun notifySessionExpired(context: Context) {
        if (!com.fakegps.mocklocation.util.PermissionHelper.hasNotificationPermission(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SESSION_EXPIRED_DIALOG", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            NOTIF_ID_EXPIRED,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("🔴 Mock Location Session Expired")
            .setContentText("Your simulation time has ended. Tap to extend +2 hours with rewarded ad or reconnect.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your simulation session has ended. Tap below to extend by +2 Hours by watching an ad, or reconnect for +20 mins free."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_bolt, "Extend +2 Hours", pendingOpen)
            .addAction(R.drawable.ic_refresh, "Reconnect (+20m)", pendingOpen)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID_EXPIRED, notification)
    }

    private fun cancelNotification(context: Context, notifId: Int) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(notifId)
        } catch (ignored: Exception) {}
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Session Timer & Expiry Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority notifications for session duration countdowns and expiration warnings."
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
