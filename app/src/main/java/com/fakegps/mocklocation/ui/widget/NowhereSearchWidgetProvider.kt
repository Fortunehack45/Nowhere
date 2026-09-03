package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity

class NowhereSearchWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SEARCH_WIDGET_TELEPORT = "com.fakegps.mocklocation.ACTION_SEARCH_WIDGET_TELEPORT"
        const val ACTION_SEARCH_WIDGET_STOP = "com.fakegps.mocklocation.ACTION_SEARCH_WIDGET_STOP"
        const val ACTION_UPDATE_SEARCH_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_SEARCH_WIDGET"

        fun updateAllSearchWidgets(context: Context) {
            val intent = Intent(context, NowhereSearchWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_SEARCH_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateSearchWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val sessionPrefs = SessionPreferences(context)

        when (intent.action) {
            ACTION_SEARCH_WIDGET_TELEPORT -> {
                val lat = sessionPrefs.lastLatitude
                val lon = sessionPrefs.lastLongitude
                sessionPrefs.isSessionActive = true
                sessionPrefs.activeMode = "FIXED"

                val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START_FIXED
                    putExtra(MockLocationService.EXTRA_LATITUDE, lat)
                    putExtra(MockLocationService.EXTRA_LONGITUDE, lon)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Toast.makeText(context, "Teleported to ${String.format("%.4f, %.4f", lat, lon)}", Toast.LENGTH_SHORT).show()
                updateAllSearchWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
                NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(context)
            }
            ACTION_SEARCH_WIDGET_STOP -> {
                val stopIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_STOP
                }
                context.startService(stopIntent)
                Toast.makeText(context, "Simulation Stopped", Toast.LENGTH_SHORT).show()
                updateAllSearchWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
                NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(context)
            }
            ACTION_UPDATE_SEARCH_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereSearchWidgetProvider::class.java))
                for (id in ids) {
                    updateSearchWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateSearchWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_search_layout)
        val sessionPrefs = SessionPreferences(context)

        val isActive = sessionPrefs.isSessionActive
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
        if (isActive) {
            views.setTextViewText(R.id.tvSearchWidgetStatus, "ACTIVE")
            views.setTextColor(R.id.tvSearchWidgetStatus, primaryColor)
            views.setTextViewText(R.id.btnSearchWidgetTeleport, "Engaged")
        } else {
            views.setTextViewText(R.id.tvSearchWidgetStatus, "STANDBY")
            views.setTextColor(R.id.tvSearchWidgetStatus, ContextCompat.getColor(context, R.color.text_muted))
            views.setTextViewText(R.id.btnSearchWidgetTeleport, "Teleport")
        }

        views.setTextViewText(
            R.id.tvSearchWidgetCoords,
            String.format("%.5f°, %.5f°", sessionPrefs.lastLatitude, sessionPrefs.lastLongitude)
        )

        // Open App with Search Focused
        val openSearchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("focus_search", true)
            setPackage(context.packageName)
        }
        val openSearchPendingIntent = PendingIntent.getActivity(
            context,
            401,
            openSearchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnSearchWidgetSearch, openSearchPendingIntent)
        views.setOnClickPendingIntent(R.id.searchWidgetRoot, openSearchPendingIntent)

        // Teleport Action Intent
        val teleportIntent = Intent(context, NowhereSearchWidgetProvider::class.java).apply {
            action = ACTION_SEARCH_WIDGET_TELEPORT
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnSearchWidgetTeleport,
            PendingIntent.getBroadcast(context, 402, teleportIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Stop Action Intent
        val stopIntent = Intent(context, NowhereSearchWidgetProvider::class.java).apply {
            action = ACTION_SEARCH_WIDGET_STOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnSearchWidgetStop,
            PendingIntent.getBroadcast(context, 403, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
