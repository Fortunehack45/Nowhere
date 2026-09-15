package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity

class NowhereGameBoostWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "GameBoostWidget"
        const val ACTION_GAME_BOOST_TOGGLE = "com.fakegps.mocklocation.ACTION_GAME_BOOST_TOGGLE"

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
            val isMockActive = sessionPrefs.isSessionActive

            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            views.setInt(R.id.ivWidgetGameLogo, "setColorFilter", primaryColor)
            views.setTextColor(R.id.tvWidgetGameTitle, primaryColor)
            views.setTextColor(R.id.tvWidgetGameData, primaryColor)
            views.setInt(R.id.ivWidgetGameBoostToggleBg, "setColorFilter", primaryColor)

            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.gameBoostWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setInt(R.id.btnWidgetGameSwitch, "setBackgroundResource", bgButtonRes)
            views.setTextColor(R.id.tvWidgetGameName, primaryText)
            views.setTextColor(R.id.tvWidgetGameStats, secondaryText)
            views.setTextColor(R.id.btnWidgetGameSwitch, primaryColor)

            if (isMockActive) {
                views.setTextViewText(R.id.tvWidgetGameStatus, "ACTIVE")
                views.setTextColor(R.id.tvWidgetGameStatus, primaryColor)
                views.setTextViewText(R.id.btnWidgetGameBoostToggle, "Open App")
                views.setTextViewText(R.id.tvWidgetGameName, "Mock GPS Active")
                views.setTextViewText(R.id.tvWidgetGameStats, "Coordinates: ${sessionPrefs.lastLatitude}, ${sessionPrefs.lastLongitude}")
                views.setTextViewText(R.id.tvWidgetGameData, "Low Latency Simulation")
            } else {
                views.setTextViewText(R.id.tvWidgetGameStatus, "STANDBY")
                views.setTextColor(R.id.tvWidgetGameStatus, secondaryText)
                views.setTextViewText(R.id.btnWidgetGameBoostToggle, "Launch")
                views.setTextViewText(R.id.tvWidgetGameName, "Nowhere GPS")
                views.setTextViewText(R.id.tvWidgetGameStats, "Tap to open Nowhere")
                views.setTextViewText(R.id.tvWidgetGameData, "Simulation Ready")
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            views.setOnClickPendingIntent(R.id.layoutWidgetGameBoostToggle, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.btnWidgetGameBoostToggle, openAppPendingIntent)

            return views
        }

        fun updateGameBoostWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(context)
            val views = when (prefs.appTheme) {
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
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateGameBoostWidgetDirect(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_GAME_BOOST_TOGGLE) {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openAppIntent)
        }
    }
}
