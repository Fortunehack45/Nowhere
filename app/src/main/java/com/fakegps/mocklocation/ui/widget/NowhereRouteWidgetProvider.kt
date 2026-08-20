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
        private const val TAG = "NowhereRouteWidget"
        const val ACTION_ROUTE_WIDGET_PLAY_PAUSE = "com.fakegps.mocklocation.ACTION_ROUTE_WIDGET_PLAY_PAUSE"
        const val ACTION_ROUTE_WIDGET_STOP = "com.fakegps.mocklocation.ACTION_ROUTE_WIDGET_STOP"
        const val ACTION_UPDATE_ROUTE_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_ROUTE_WIDGET"

        fun updateAllRouteWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereRouteWidgetProvider::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateRouteWidgetDirect(context, appWidgetManager, id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Route widget update: ${e.message}")
            }
        }

        fun updateRouteWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_route_layout)
            val sessionPrefs = SessionPreferences(context)
            val settingsPrefs = AppSettingsPreferences(context)

            val isActive = sessionPrefs.isSessionActive && sessionPrefs.activeMode == "ROUTE"
            val waypoints = sessionPrefs.getWaypoints()
            val speedKmh = sessionPrefs.lastSpeedKmh

            val totalDistMeters = sessionPrefs.routeTotalDistanceMeters
            val coveredDistMeters = sessionPrefs.routeCoveredDistanceMeters
            val remainingDistMeters = sessionPrefs.routeRemainingDistanceMeters

            val useImperial = settingsPrefs.useImperialUnits
            val totalFormatted: String
            val coveredFormatted: String
            val remainingFormatted: String

            if (useImperial) {
                val totalMiles = totalDistMeters * 0.000621371
                val coveredMiles = coveredDistMeters * 0.000621371
                val remainingMiles = remainingDistMeters * 0.000621371
                totalFormatted = String.format("%.2f mi", totalMiles)
                coveredFormatted = String.format("%.2f mi", coveredMiles)
                remainingFormatted = String.format("%.2f mi left", remainingMiles)
            } else {
                val totalKm = totalDistMeters / 1000.0
                val coveredKm = coveredDistMeters / 1000.0
                val remainingKm = remainingDistMeters / 1000.0
                totalFormatted = String.format("%.2f km", totalKm)
                coveredFormatted = String.format("%.2f km", coveredKm)
                remainingFormatted = String.format("%.2f km left", remainingKm)
            }

            val speedMps = (speedKmh / 3.6f).coerceAtLeast(0.1f)
            val etaSeconds = if (speedMps > 0.1f && remainingDistMeters > 0) (remainingDistMeters / speedMps).toLong() else 0L
            val etaFormatted = when {
                etaSeconds <= 0 -> "Arriving"
                etaSeconds >= 3600 -> String.format(java.util.Locale.US, "%dh %02dm", etaSeconds / 3600, (etaSeconds % 3600) / 60)
                etaSeconds >= 60 -> String.format(java.util.Locale.US, "%dm %02ds", etaSeconds / 60, etaSeconds % 60)
                else -> String.format(java.util.Locale.US, "%ds", etaSeconds)
            }

            val progressPercent = if (totalDistMeters > 0) ((coveredDistMeters / totalDistMeters) * 100).toInt().coerceIn(0, 100) else 0

            if (isActive) {
                views.setTextViewText(R.id.tvWidgetRouteStatus, "RUNNING")
                views.setTextColor(R.id.tvWidgetRouteStatus, ContextCompat.getColor(context, R.color.badge_active_text))
                views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Pause")
                views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • $progressPercent%")
                views.setTextViewText(R.id.tvWidgetRouteDistance, "Covered: $coveredFormatted / $totalFormatted")
                views.setTextViewText(R.id.tvWidgetRouteRemaining, "$remainingFormatted • ETA: $etaFormatted")
                views.setProgressBar(R.id.pbWidgetRoute, 100, progressPercent, false)
            } else {
                views.setTextViewText(R.id.tvWidgetRouteStatus, "STANDBY")
                views.setTextColor(R.id.tvWidgetRouteStatus, ContextCompat.getColor(context, R.color.text_muted))
                views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Start")
                views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • Ready")
                views.setTextViewText(R.id.tvWidgetRouteDistance, "Total: $totalFormatted")
                views.setTextViewText(R.id.tvWidgetRouteRemaining, "Ready to start")
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

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateRouteWidgetDirect(context, appWidgetManager, appWidgetId)
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
                updateAllRouteWidgets(context)
            }
        }
    }
}
