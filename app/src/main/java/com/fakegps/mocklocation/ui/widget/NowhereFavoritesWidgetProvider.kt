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
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.ui.SettingsActivity
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowhereFavoritesWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_FAV_TELEPORT = "com.fakegps.mocklocation.ACTION_FAV_TELEPORT"
        const val ACTION_FAV_STOP = "com.fakegps.mocklocation.ACTION_FAV_STOP"
        const val ACTION_UPDATE_FAV_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_FAV_WIDGET"
        const val EXTRA_FAV_LAT = "extra_fav_lat"
        const val EXTRA_FAV_LON = "extra_fav_lon"
        const val EXTRA_FAV_NAME = "extra_fav_name"

        fun updateAllFavoritesWidgets(context: Context) {
            val intent = Intent(context, NowhereFavoritesWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_FAV_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateFavoritesWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_FAV_TELEPORT -> {
                val lat = intent.getDoubleExtra(EXTRA_FAV_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_FAV_LON, 0.0)
                val name = intent.getStringExtra(EXTRA_FAV_NAME) ?: "Destination"

                val sessionPrefs = SessionPreferences(context)
                sessionPrefs.lastLatitude = lat
                sessionPrefs.lastLongitude = lon
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

                Toast.makeText(context, "Teleported to $name", Toast.LENGTH_SHORT).show()
                updateAllFavoritesWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
                NowhereSearchWidgetProvider.updateAllSearchWidgets(context)
            }
            ACTION_FAV_STOP -> {
                val stopIntent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_STOP
                }
                context.startService(stopIntent)
                Toast.makeText(context, "Simulation Stopped", Toast.LENGTH_SHORT).show()
                updateAllFavoritesWidgets(context)
                NowhereAppWidgetProvider.updateAllWidgets(context)
                NowhereRouteWidgetProvider.updateAllRouteWidgets(context)
                NowhereSearchWidgetProvider.updateAllSearchWidgets(context)
            }
            ACTION_UPDATE_FAV_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereFavoritesWidgetProvider::class.java))
                for (id in ids) {
                    updateFavoritesWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateFavoritesWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_favorites_layout)
        val sessionPrefs = SessionPreferences(context)
        val settingsPrefs = AppSettingsPreferences(context)

        val isActive = sessionPrefs.isSessionActive
        val curLat = sessionPrefs.lastLatitude
        val curLon = sessionPrefs.lastLongitude

        val s1Name = settingsPrefs.widgetSlot1Name
        val s1Lat = settingsPrefs.widgetSlot1Lat
        val s1Lon = settingsPrefs.widgetSlot1Lon

        val s2Name = settingsPrefs.widgetSlot2Name
        val s2Lat = settingsPrefs.widgetSlot2Lat
        val s2Lon = settingsPrefs.widgetSlot2Lon

        val s3Name = settingsPrefs.widgetSlot3Name
        val s3Lat = settingsPrefs.widgetSlot3Lat
        val s3Lon = settingsPrefs.widgetSlot3Lon

        // Determine which slot is actively matched
        val matchSlot1 = isActive && GeoUtils.calculateDistanceMeters(curLat, curLon, s1Lat, s1Lon) < 150.0
        val matchSlot2 = isActive && GeoUtils.calculateDistanceMeters(curLat, curLon, s2Lat, s2Lon) < 150.0
        val matchSlot3 = isActive && GeoUtils.calculateDistanceMeters(curLat, curLon, s3Lat, s3Lon) < 150.0

        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
        views.setInt(R.id.ivFavWidgetLogo, "setColorFilter", primaryColor)
        views.setTextColor(R.id.tvFavWidgetTitle, primaryColor)
        views.setTextColor(R.id.btnFavWidgetEdit, primaryColor)
        views.setTextColor(R.id.btnFavWidgetStop, primaryColor)

        // Configure Active Pill Indicator
        if (isActive) {
            val activeName = when {
                matchSlot1 -> s1Name.uppercase()
                matchSlot2 -> s2Name.uppercase()
                matchSlot3 -> s3Name.uppercase()
                else -> "ACTIVE"
            }
            views.setTextViewText(R.id.tvFavWidgetStatus, activeName)
            views.setTextColor(R.id.tvFavWidgetStatus, primaryColor)
        } else {
            views.setTextViewText(R.id.tvFavWidgetStatus, "STANDBY")
            views.setTextColor(R.id.tvFavWidgetStatus, ContextCompat.getColor(context, R.color.text_muted))
        }

        // Bind Slots
        fun bindSlot(viewId: Int, reqCode: Int, name: String, lat: Double, lon: Double, isMatched: Boolean) {
            views.setTextViewText(viewId, if (isMatched) "✓ $name" else name)
            if (isMatched) {
                views.setInt(viewId, "setBackgroundResource", R.drawable.bg_widget_button)
                views.setTextColor(viewId, primaryColor)
            } else {
                views.setInt(viewId, "setBackgroundResource", R.drawable.bg_widget_button)
                views.setTextColor(viewId, ContextCompat.getColor(context, R.color.text_primary))
            }

            val teleIntent = Intent(context, NowhereFavoritesWidgetProvider::class.java).apply {
                action = ACTION_FAV_TELEPORT
                putExtra(EXTRA_FAV_LAT, lat)
                putExtra(EXTRA_FAV_LON, lon)
                putExtra(EXTRA_FAV_NAME, name)
                setPackage(context.packageName)
            }
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(context, reqCode, teleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }

        bindSlot(R.id.btnFavSlot1, 310, s1Name, s1Lat, s1Lon, matchSlot1)
        bindSlot(R.id.btnFavSlot2, 311, s2Name, s2Lat, s2Lon, matchSlot2)
        bindSlot(R.id.btnFavSlot3, 312, s3Name, s3Lat, s3Lon, matchSlot3)

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.favoritesWidgetRoot,
            PendingIntent.getActivity(context, 301, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Edit Destinations Action (Opens Settings Screen)
        val editIntent = Intent(context, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_widget_config", true)
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnFavWidgetEdit,
            PendingIntent.getActivity(context, 303, editIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Stop Intent
        val stopIntent = Intent(context, NowhereFavoritesWidgetProvider::class.java).apply {
            action = ACTION_FAV_STOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.btnFavWidgetStop,
            PendingIntent.getBroadcast(context, 302, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        // Asynchronously resolve active location name or country for coordinates readout
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locText = LocationNameResolver.resolveLocationName(context, curLat, curLon)
                views.setTextViewText(R.id.tvFavCurrentLocation, locText)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                views.setTextViewText(R.id.tvFavCurrentLocation, String.format("%.4f°, %.4f°", curLat, curLon))
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
