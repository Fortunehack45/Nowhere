package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.FloatingJoystickService
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowhereAppWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TELEPORT = "com.fakegps.mocklocation.ACTION_WIDGET_TELEPORT"
        const val ACTION_WIDGET_JOYSTICK = "com.fakegps.mocklocation.ACTION_WIDGET_JOYSTICK"
        const val ACTION_UPDATE_WIDGET_STATE = "com.fakegps.mocklocation.ACTION_UPDATE_WIDGET_STATE"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, NowhereAppWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET_STATE
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_WIDGET_TELEPORT -> {
                val sessionPrefs = SessionPreferences(context)
                if (sessionPrefs.isSessionActive) {
                    val stopIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    val startIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_FIXED
                        putExtra(MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                        putExtra(MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                }
                updateAllWidgets(context)
            }
            ACTION_WIDGET_JOYSTICK -> {
                FloatingJoystickService.start(context)
                updateAllWidgets(context)
            }
            ACTION_UPDATE_WIDGET_STATE, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereAppWidgetProvider::class.java))
                for (id in ids) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_layout)
        val sessionPrefs = SessionPreferences(context)

        val isActive = sessionPrefs.isSessionActive
        val lat = sessionPrefs.lastLatitude
        val lon = sessionPrefs.lastLongitude

        if (isActive) {
            views.setTextViewText(R.id.tvWidgetStatus, "● ACTIVE")
            views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.badge_active_text))
            views.setTextViewText(R.id.btnWidgetTeleport, "■ Stop")
        } else {
            views.setTextViewText(R.id.tvWidgetStatus, "READY")
            views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.text_muted))
            views.setTextViewText(R.id.btnWidgetTeleport, "⚡ Inject GPS")
        }

        views.setTextViewText(R.id.tvWidgetCoords, String.format("%.5f° N, %.5f° W", lat, lon))

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.btnWidgetOpenApp, openAppPendingIntent)

        // Teleport Action Intent
        val teleportIntent = Intent(context, NowhereAppWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TELEPORT
        }
        val teleportPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            teleportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetTeleport, teleportPendingIntent)

        // Joystick Action Intent
        val joystickIntent = Intent(context, NowhereAppWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_JOYSTICK
        }
        val joystickPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            joystickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetJoystick, joystickPendingIntent)

        CoroutineScope(Dispatchers.IO).launch {
            val placeName = LocationNameResolver.resolveLocationName(context, lat, lon)
            views.setTextViewText(R.id.tvWidgetLocationName, "📍 $placeName")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
