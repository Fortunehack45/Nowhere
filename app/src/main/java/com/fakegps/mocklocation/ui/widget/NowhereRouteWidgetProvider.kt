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

        private fun buildRouteRemoteViews(context: Context, isDark: Boolean): RemoteViews {
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

            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            views.setTextColor(R.id.tvRouteWidgetTitle, primaryColor)
            views.setInt(R.id.ivRouteWidgetIcon, "setColorFilter", primaryColor)
            views.setTextColor(R.id.tvWidgetRouteSpeed, primaryColor)
            views.setTextColor(R.id.tvLabelFrom, primaryColor)
            views.setTextColor(R.id.tvLabelTo, primaryColor)
            views.setTextColor(R.id.tvWidgetRouteRemaining, primaryColor)
            views.setTextColor(R.id.btnWidgetRouteStop, primaryColor)
            views.setInt(R.id.ivWidgetRoutePlayPauseBg, "setColorFilter", primaryColor)

            // Dynamic Dark / Light theme styling
            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.routeWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setInt(R.id.btnWidgetRouteStop, "setBackgroundResource", bgButtonRes)
            views.setTextColor(R.id.tvRouteOrigin, primaryText)
            views.setTextColor(R.id.tvRouteDestination, primaryText)
            views.setTextColor(R.id.tvWidgetRouteWaypoints, secondaryText)
            views.setTextColor(R.id.tvWidgetRouteDistance, secondaryText)

            if (isActive) {
                views.setTextViewText(R.id.tvWidgetRouteStatus, "RUNNING")
                views.setTextColor(R.id.tvWidgetRouteStatus, primaryColor)
                views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Pause")
                views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • $progressPercent%")
                views.setTextViewText(R.id.tvWidgetRouteDistance, "Covered: $coveredFormatted / $totalFormatted")
                views.setTextViewText(R.id.tvWidgetRouteRemaining, "$remainingFormatted • ETA: $etaFormatted")
                views.setProgressBar(R.id.pbWidgetRoute, 100, progressPercent, false)
            } else {
                views.setTextViewText(R.id.tvWidgetRouteStatus, "STANDBY")
                views.setTextColor(R.id.tvWidgetRouteStatus, secondaryText)
                views.setTextViewText(R.id.btnWidgetRoutePlayPause, "Start")
                views.setTextViewText(R.id.tvWidgetRouteWaypoints, "${waypoints.size} Waypoints • Ready")
                views.setTextViewText(R.id.tvWidgetRouteDistance, "Total: $totalFormatted")
                views.setTextViewText(R.id.tvWidgetRouteRemaining, "Ready to start")
                views.setProgressBar(R.id.pbWidgetRoute, 100, 0, false)
            }

            if (waypoints.isNotEmpty()) {
                val origin = waypoints.first()
                val dest = waypoints.last()
                val originCoords = String.format(java.util.Locale.US, "%.4f, %.4f", origin.latitude, origin.longitude)
                val destCoords = String.format(java.util.Locale.US, "%.4f, %.4f", dest.latitude, dest.longitude)
                val originName = if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
                    sessionPrefs.lastLocationName
                } else {
                    originCoords
                }
                views.setTextViewText(R.id.tvRouteOrigin, originName)
                views.setTextViewText(R.id.tvRouteDestination, destCoords)
            } else {
                views.setTextViewText(R.id.tvRouteOrigin, "No Route Planned")
                views.setTextViewText(R.id.tvRouteDestination, "Tap to plan route")
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

            return views
        }

        fun updateRouteWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(context)
            val finalViews = when (prefs.appTheme) {
                "LIGHT" -> buildRouteRemoteViews(context, isDark = false)
                "DARK" -> buildRouteRemoteViews(context, isDark = true)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(buildRouteRemoteViews(context, isDark = false), buildRouteRemoteViews(context, isDark = true))
                    } else {
                        val isDark = com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(context)
                        buildRouteRemoteViews(context, isDark)
                    }
                }
            }
            appWidgetManager.updateAppWidget(appWidgetId, finalViews)
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
