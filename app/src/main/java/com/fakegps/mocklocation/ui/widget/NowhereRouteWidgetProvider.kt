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
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowhereRouteWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ROUTE_WIDGET_PLAY_PAUSE = "com.fakegps.mocklocation.ACTION_ROUTE_WIDGET_PLAY_PAUSE"
        const val ACTION_ROUTE_WIDGET_STOP = "com.fakegps.mocklocation.ACTION_ROUTE_WIDGET_STOP"
        const val ACTION_UPDATE_ROUTE_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_ROUTE_WIDGET"

        fun updateAllRouteWidgets(context: Context) {
            val intent = Intent(context, NowhereRouteWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_ROUTE_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateRouteWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_ROUTE_WIDGET_PLAY_PAUSE -> {
                val sessionPrefs = SessionPreferences(context)
                val isRunning = sessionPrefs.isSessionActive && sessionPrefs.activeMode == "ROUTE"

                if (isRunning) {
                    val isPaused = intent.getBooleanExtra("is_paused", false)
                    val action = if (isPaused) MockLocationService.ACTION_RESUME_ROUTE else MockLocationService.ACTION_PAUSE_ROUTE
                    val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                        this.action = action
                    }
                    context.startService(serviceIntent)
                } else {
                    val waypoints = sessionPrefs.getWaypoints()
                    if (waypoints.size >= 2) {
                        val startIntent = Intent(context, MockLocationService::class.java).apply {
                            action = MockLocationService.ACTION_START_ROUTE
                            putExtra(MockLocationService.EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                            putExtra(MockLocationService.EXTRA_IS_LOOPING, sessionPrefs.isLooping)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                        Toast.makeText(context, "Route Simulation Started", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Please plot route waypoints in Nowhere first", Toast.LENGTH_SHORT).show()
                    }
                }
                updateAllRouteWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
            }
            ACTION_ROUTE_WIDGET_STOP -> {
                val stopIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_STOP
                }
                context.startService(stopIntent)
                Toast.makeText(context, "Simulation Stopped", Toast.LENGTH_SHORT).show()
                updateAllRouteWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
            }
            ACTION_UPDATE_ROUTE_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereRouteWidgetProvider::class.java))
                for (id in ids) {
                    updateRouteWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateRouteWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_route_layout)
        val sessionPrefs = SessionPreferences(context)
        val settingsPrefs = AppSettingsPreferences(context)

        val isActive = sessionPrefs.isSessionActive && sessionPrefs.activeMode == "ROUTE"
        val waypoints = sessionPrefs.getWaypoints()
        val speedKmh = sessionPrefs.lastSpeedKmh

        if (isActive) {
            views.setTextViewText(R.id.tvWidgetRouteStatus, "RUNNING")
            views.setTextColor(R.id.tvWidgetRouteStatus, ContextCompat.getColor(context, R.color.badge_active_text))
            views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Pause")
            views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • Active Route")
            views.setProgressBar(R.id.pbWidgetRoute, 100, 60, false)
        } else {
            views.setTextViewText(R.id.tvWidgetRouteStatus, "STANDBY")
            views.setTextColor(R.id.tvWidgetRouteStatus, ContextCompat.getColor(context, R.color.text_muted))
            views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Start")
            views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • Ready")
            views.setProgressBar(R.id.pbWidgetRoute, 100, 0, false)
        }

        views.setTextViewText(R.id.tvWidgetRouteSpeed, settingsPrefs.formatSpeed(speedKmh))

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.routeWidgetRoot, openAppPendingIntent)

        // Play / Pause Action Intent
        val playPauseIntent = Intent(context, NowhereRouteWidgetProvider::class.java).apply {
            action = ACTION_ROUTE_WIDGET_PLAY_PAUSE
            putExtra("is_paused", false)
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnWidgetRoutePlayPause,
            PendingIntent.getBroadcast(context, 102, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Stop Action Intent
        val stopIntent = Intent(context, NowhereRouteWidgetProvider::class.java).apply {
            action = ACTION_ROUTE_WIDGET_STOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnWidgetRouteStop,
            PendingIntent.getBroadcast(context, 103, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Resolve Origin and Destination Place Names Asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val originText: String
                val destText: String

                if (waypoints.isNotEmpty()) {
                    val origin = waypoints.first()
                    val dest = waypoints.last()

                    originText = LocationNameResolver.resolveLocationName(context, origin.latitude, origin.longitude)
                    destText = if (waypoints.size > 1) {
                        LocationNameResolver.resolveLocationName(context, dest.latitude, dest.longitude)
                    } else {
                        "Tap map to set endpoint"
                    }
                } else {
                    originText = String.format("%.4f°, %.4f°", sessionPrefs.lastLatitude, sessionPrefs.lastLongitude)
                    destText = "Open map to plot route"
                }

                views.setTextViewText(R.id.tvRouteOrigin, originText)
                views.setTextViewText(R.id.tvRouteDestination, destText)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
