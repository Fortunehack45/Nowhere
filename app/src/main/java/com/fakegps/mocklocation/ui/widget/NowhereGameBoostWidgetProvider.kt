package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.vpn.NowhereApiClient
import com.fakegps.mocklocation.vpn.NowhereVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowhereGameBoostWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "GameBoostWidget"
        const val ACTION_GAME_BOOST_TOGGLE = "com.fakegps.mocklocation.ACTION_GAME_BOOST_TOGGLE"
        const val ACTION_UPDATE_GAME_BOOST = "com.fakegps.mocklocation.ACTION_UPDATE_GAME_BOOST"

        fun updateAllGameBoostWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereGameBoostWidgetProvider::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateGameBoostWidgetDirect(context, appWidgetManager, id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Game Boost widget update: ${e.message}")
            }
        }

        private fun buildGameBoostRemoteViews(context: Context, isDark: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_game_boost_layout)
            val sessionPrefs = SessionPreferences(context)
            val isRunning = NowhereVpnService.isRunning
            val stats = NowhereVpnService.trafficStats.value

            val activeGameName = sessionPrefs.lastSelectedGameName.ifEmpty { "Call of Duty: Mobile / Warzone" }
            val activeGameIcon = sessionPrefs.lastSelectedGameIcon.ifEmpty { "🎯" }

            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            views.setInt(R.id.ivWidgetGameLogo, "setColorFilter", primaryColor)
            views.setTextColor(R.id.tvWidgetGameTitle, primaryColor)
            views.setTextColor(R.id.tvWidgetGameData, primaryColor)
            views.setInt(R.id.ivWidgetGameBoostToggleBg, "setColorFilter", primaryColor)

            // Dynamic Dark / Light theme styling
            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.gameBoostWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setInt(R.id.btnWidgetGameSwitch, "setBackgroundResource", bgButtonRes)
            views.setTextColor(R.id.tvWidgetGameName, primaryText)
            views.setTextColor(R.id.tvWidgetGameStats, secondaryText)
            views.setTextColor(R.id.btnWidgetGameSwitch, primaryColor)

            if (isRunning) {
                views.setTextViewText(R.id.tvWidgetGameStatus, "BOOSTED")
                views.setTextColor(R.id.tvWidgetGameStatus, primaryColor)
                views.setTextViewText(R.id.btnWidgetGameBoostToggle, "Stop Boost")
                views.setTextViewText(R.id.tvWidgetGameName, activeGameName)
                views.setTextViewText(R.id.tvWidgetGameStats, "FastPath Active • 14ms • DSCP 46 EF")
                views.setTextViewText(R.id.tvWidgetGameData, "↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})")
            } else {
                views.setTextViewText(R.id.tvWidgetGameStatus, "READY")
                views.setTextColor(R.id.tvWidgetGameStatus, secondaryText)
                views.setTextViewText(R.id.btnWidgetGameBoostToggle, "Boost Now")
                views.setTextViewText(R.id.tvWidgetGameName, activeGameName)
                views.setTextViewText(R.id.tvWidgetGameStats, "Google BBR FastPath • 10 Gbps Pipeline")
                views.setTextViewText(R.id.tvWidgetGameData, "↓ 0.00 KB  ↑ 0.00 KB (Standby)")
            }

            // Open App to Game Boost Tab Intent
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_VPN_DIALOG", true)
                putExtra("INITIAL_TAB", 1)
                setPackage(context.packageName)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                301,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.gameBoostWidgetRoot, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.btnWidgetGameSwitch, openAppPendingIntent)

            // 1-Tap Toggle Intent
            val toggleIntent = Intent(context, NowhereGameBoostWidgetProvider::class.java).apply {
                action = ACTION_GAME_BOOST_TOGGLE
                setPackage(context.packageName)
            }
            views.setOnClickPendingIntent(
                R.id.btnWidgetGameBoostToggle,
                PendingIntent.getBroadcast(context, 302, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            return views
        }

        fun updateGameBoostWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(context)
            val finalViews = when (prefs.appTheme) {
                "LIGHT" -> buildGameBoostRemoteViews(context, isDark = false)
                "DARK" -> buildGameBoostRemoteViews(context, isDark = true)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(buildGameBoostRemoteViews(context, isDark = false), buildGameBoostRemoteViews(context, isDark = true))
                    } else {
                        val isDark = com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(context)
                        buildGameBoostRemoteViews(context, isDark)
                    }
                }
            }
            appWidgetManager.updateAppWidget(appWidgetId, finalViews)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateGameBoostWidgetDirect(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_GAME_BOOST_TOGGLE -> {
                val sessionPrefs = SessionPreferences(context)
                if (NowhereVpnService.isRunning) {
                    val stopIntent = Intent(context, NowhereVpnService::class.java).apply {
                        action = NowhereVpnService.ACTION_DISCONNECT
                    }
                    context.startService(stopIntent)
                    Toast.makeText(context, "⏹️ Game Booster Disconnected", Toast.LENGTH_SHORT).show()
                } else {
                    val vpnIntent = VpnService.prepare(context)
                    if (vpnIntent != null) {
                        // Open app to grant permission
                        val openAppIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("OPEN_VPN_DIALOG", true)
                            putExtra("INITIAL_TAB", 1)
                        }
                        context.startActivity(openAppIntent)
                        Toast.makeText(context, "⚠️ Please grant VPN permission in app", Toast.LENGTH_SHORT).show()
                    } else {
                        val gameId = sessionPrefs.lastSelectedGameId.ifEmpty { "cod_mobile" }
                        val gameName = sessionPrefs.lastSelectedGameName.ifEmpty { "Call of Duty: Mobile / Warzone" }
                        Toast.makeText(context, "Optimizing FastPath routing for $gameName...", Toast.LENGTH_SHORT).show()

                        CoroutineScope(Dispatchers.IO).launch {
                            val result = NowhereApiClient.optimizeGame(context, gameId)
                            if (result.isSuccess) {
                                val tunnelConfig = result.getOrNull()
                                if (tunnelConfig != null) {
                                    NowhereVpnService.startWithTunnelResponse(
                                        context = context,
                                        response = tunnelConfig,
                                        customName = "Game Boost: $gameName"
                                    )
                                }
                            }
                        }
                    }
                }
                updateAllGameBoostWidgets(context)
                NowhereVpnWidgetProvider.updateAllVpnWidgets(context)
            }
            ACTION_UPDATE_GAME_BOOST -> {
                updateAllGameBoostWidgets(context)
            }
        }
    }
}
