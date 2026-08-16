package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity

class NowhereIconWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ICON_WIDGET_CLICK = "com.fakegps.mocklocation.ACTION_ICON_WIDGET_CLICK"
        const val ACTION_UPDATE_ICON_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_ICON_WIDGET"

        fun updateAllIconWidgets(context: Context) {
            val intent = Intent(context, NowhereIconWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_ICON_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateIconWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_ICON_WIDGET_CLICK -> {
                val sessionPrefs = SessionPreferences(context)
                if (sessionPrefs.isSessionActive) {
                    // If active, launch Nowhere dashboard directly
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(openIntent)
                } else {
                    // If inactive, 1-tap start last simulated location
                    sessionPrefs.isSessionActive = true
                    sessionPrefs.activeMode = "FIXED"

                    val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_FIXED
                        putExtra(MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                        putExtra(MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    Toast.makeText(context, "Nowhere Teleport Active", Toast.LENGTH_SHORT).show()
                    updateAllIconWidgets(context)
                    NowhereAppWidgetProvider.updateAllWidgets(context)
                    NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
                    NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(context)
                    NowhereSearchWidgetProvider.updateAllSearchWidgets(context)
                }
            }
            ACTION_UPDATE_ICON_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereIconWidgetProvider::class.java))
                for (id in ids) {
                    updateIconWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateIconWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_icon_layout)
        val sessionPrefs = SessionPreferences(context)
        val isActive = sessionPrefs.isSessionActive

        // Dynamic Icon Appearance & Status Dot
        if (isActive) {
            views.setInt(R.id.flIconContainer, "setBackgroundResource", R.drawable.bg_app_icon_squircle_active)
            views.setViewVisibility(R.id.ivIconStatusDot, View.VISIBLE)
        } else {
            views.setInt(R.id.flIconContainer, "setBackgroundResource", R.drawable.bg_app_icon_squircle)
            views.setViewVisibility(R.id.ivIconStatusDot, View.GONE)
        }

        // Title under the icon with high-contrast text shadow matching launcher styling
        views.setTextViewText(R.id.tvWidgetAppName, context.getString(R.string.app_name))

        val clickIntent = Intent(context, NowhereIconWidgetProvider::class.java).apply {
            action = ACTION_ICON_WIDGET_CLICK
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            401,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetIconRoot, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
