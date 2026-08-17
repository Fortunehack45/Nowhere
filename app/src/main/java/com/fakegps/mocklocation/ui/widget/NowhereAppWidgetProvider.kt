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
import com.fakegps.mocklocation.service.FloatingJoystickService
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.util.LocationNameResolver
import com.fakegps.mocklocation.util.PermissionHelper
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
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
            NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(context)
            NowhereSearchWidgetProvider.updateAllSearchWidgets(context)
            NowhereIconWidgetProvider.updateAllIconWidgets(context)
            NowhereVpnWidgetProvider.updateAllVpnWidgets(context)
            NowhereWeatherWidgetProvider.updateAllWeatherWidgets(context)
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
                    Toast.makeText(context, "Nowhere: Location Simulation Stopped", Toast.LENGTH_SHORT).show()
                } else {
                    val startIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_FIXED
                        putExtra(MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                        putExtra(MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                        putExtra(MockLocationService.EXTRA_ALTITUDE, sessionPrefs.lastAltitude)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                    Toast.makeText(context, "Nowhere: Injected GPS Started", Toast.LENGTH_SHORT).show()
                }
                updateAllWidgets(context)
            }
            ACTION_WIDGET_JOYSTICK -> {
                if (!PermissionHelper.canDrawOverlays(context)) {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("open_overlay_permission", true)
                        setPackage(context.packageName)
                    }
                    context.startActivity(openIntent)
                    Toast.makeText(context, "Please allow 'Display over other apps' to use floating joystick", Toast.LENGTH_LONG).show()
                } else {
                    FloatingJoystickService.start(context)
                    val sessionPrefs = SessionPreferences(context)
                    val startIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_JOYSTICK
                        putExtra(MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                        putExtra(MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                        putExtra(MockLocationService.EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                    Toast.makeText(context, "Floating Joystick Activated", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(context)
                }
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
            views.setTextViewText(R.id.tvWidgetStatus, "ACTIVE")
            views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.badge_active_text))
            views.setTextViewText(R.id.btnWidgetTeleport, "Stop")
        } else {
            views.setTextViewText(R.id.tvWidgetStatus, "READY")
            views.setTextColor(R.id.tvWidgetStatus, ContextCompat.getColor(context, R.color.text_muted))
            views.setTextViewText(R.id.btnWidgetTeleport, "Inject GPS")
        }

        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        views.setTextViewText(R.id.tvWidgetCoords, String.format("%.5f° %s, %.5f° %s", Math.abs(lat), latDir, Math.abs(lon), lonDir))

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.btnWidgetOpenApp, openAppPendingIntent)

        // Teleport Action Intent
        val teleportIntent = Intent(context, NowhereAppWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TELEPORT
            setPackage(context.packageName)
        }
        val teleportPendingIntent = PendingIntent.getBroadcast(
            context,
            20,
            teleportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetTeleport, teleportPendingIntent)

        // Joystick Action Intent
        val joystickIntent = Intent(context, NowhereAppWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_JOYSTICK
            setPackage(context.packageName)
        }
        val joystickPendingIntent = PendingIntent.getBroadcast(
            context,
            30,
            joystickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetJoystick, joystickPendingIntent)

        CoroutineScope(Dispatchers.IO).launch {
            val placeName = LocationNameResolver.resolveLocationName(context, lat, lon)
            views.setTextViewText(R.id.tvWidgetLocationName, placeName)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
