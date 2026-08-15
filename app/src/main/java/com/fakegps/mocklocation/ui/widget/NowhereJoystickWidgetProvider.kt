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
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity

class NowhereJoystickWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_JOY_STEP = "com.fakegps.mocklocation.ACTION_JOY_STEP"
        const val ACTION_JOY_STOP = "com.fakegps.mocklocation.ACTION_JOY_STOP"
        const val ACTION_JOY_CYCLE_SPEED = "com.fakegps.mocklocation.ACTION_JOY_CYCLE_SPEED"
        const val ACTION_UPDATE_JOY_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_JOY_WIDGET"
        const val EXTRA_BEARING = "extra_bearing"

        fun updateAllJoystickWidgets(context: Context) {
            val intent = Intent(context, NowhereJoystickWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_JOY_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateJoystickWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val sessionPrefs = SessionPreferences(context)

        when (intent.action) {
            ACTION_JOY_STEP -> {
                val bearing = intent.getFloatExtra(EXTRA_BEARING, 0.0f)
                val speedKmh = sessionPrefs.lastSpeedKmh.coerceAtLeast(5.0f)
                val stepMeters = (speedKmh * 1000.0 / 3600.0) * 1.5 // 1.5 second step

                val curLat = sessionPrefs.lastLatitude
                val curLon = sessionPrefs.lastLongitude

                val (newLat, newLon) = GeoUtils.computeDestinationPoint(curLat, curLon, bearing, stepMeters)

                sessionPrefs.lastLatitude = newLat
                sessionPrefs.lastLongitude = newLon
                sessionPrefs.isSessionActive = true
                sessionPrefs.activeMode = "FIXED"

                val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START_FIXED
                    putExtra(MockLocationService.EXTRA_LATITUDE, newLat)
                    putExtra(MockLocationService.EXTRA_LONGITUDE, newLon)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                updateAllJoystickWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
            }
            ACTION_JOY_CYCLE_SPEED -> {
                val curSpeed = sessionPrefs.lastSpeedKmh
                val nextSpeed = when {
                    curSpeed < 10.0f -> 15.0f
                    curSpeed < 20.0f -> 30.0f
                    curSpeed < 45.0f -> 60.0f
                    else -> 5.0f
                }
                sessionPrefs.lastSpeedKmh = nextSpeed
                updateAllJoystickWidgets(context)
            }
            ACTION_JOY_STOP -> {
                val stopIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_STOP
                }
                context.startService(stopIntent)
                updateAllJoystickWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
            }
            ACTION_UPDATE_JOY_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereJoystickWidgetProvider::class.java))
                for (id in ids) {
                    updateJoystickWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateJoystickWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_joystick_layout)
        val sessionPrefs = SessionPreferences(context)
        val settingsPrefs = AppSettingsPreferences(context)

        val speedKmh = sessionPrefs.lastSpeedKmh
        views.setTextViewText(R.id.btnJoystickWidgetSpeed, settingsPrefs.formatSpeed(speedKmh))
        views.setTextViewText(
            R.id.tvJoyCoords,
            String.format("%.5f°, %.5f°", sessionPrefs.lastLatitude, sessionPrefs.lastLongitude)
        )

        // Directional Step Intents
        fun makeStepIntent(bearing: Float, requestCode: Int): PendingIntent {
            val stepIntent = Intent(context, NowhereJoystickWidgetProvider::class.java).apply {
                action = ACTION_JOY_STEP
                putExtra(EXTRA_BEARING, bearing)
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                stepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        views.setOnClickPendingIntent(R.id.btnJoyUp, makeStepIntent(0.0f, 201))      // North
        views.setOnClickPendingIntent(R.id.btnJoyDown, makeStepIntent(180.0f, 202))  // South
        views.setOnClickPendingIntent(R.id.btnJoyLeft, makeStepIntent(270.0f, 203))  // West
        views.setOnClickPendingIntent(R.id.btnJoyRight, makeStepIntent(90.0f, 204))   // East

        // Speed Toggle Intent
        val speedIntent = Intent(context, NowhereJoystickWidgetProvider::class.java).apply {
            action = ACTION_JOY_CYCLE_SPEED
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnJoystickWidgetSpeed,
            PendingIntent.getBroadcast(context, 205, speedIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Stop Intent (Center Button)
        val stopIntent = Intent(context, NowhereJoystickWidgetProvider::class.java).apply {
            action = ACTION_JOY_STOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnJoyCenter,
            PendingIntent.getBroadcast(context, 206, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.joystickWidgetRoot,
            PendingIntent.getActivity(context, 207, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
