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
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
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
                NowhereJoystickWidgetProvider.updateAllJoystickWidgets(context)
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
                NowhereJoystickWidgetProvider.updateAllJoystickWidgets(context)
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

        val isActive = sessionPrefs.isSessionActive
        if (isActive) {
            views.setTextViewText(R.id.tvFavWidgetStatus, "ACTIVE")
            views.setTextColor(R.id.tvFavWidgetStatus, ContextCompat.getColor(context, R.color.badge_active_text))
        } else {
            views.setTextViewText(R.id.tvFavWidgetStatus, "STANDBY")
            views.setTextColor(R.id.tvFavWidgetStatus, ContextCompat.getColor(context, R.color.text_muted))
        }

        views.setTextViewText(
            R.id.tvFavCurrentLocation,
            String.format("%.5f°, %.5f°", sessionPrefs.lastLatitude, sessionPrefs.lastLongitude)
        )

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        views.setOnClickPendingIntent(
            R.id.favoritesWidgetRoot,
            PendingIntent.getActivity(context, 301, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

        // Load Top 3 Favorites Asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val favorites = db.favoriteDao().getAllFavoritesList()

                val slot1 = favorites.getOrNull(0)
                val slot2 = favorites.getOrNull(1)
                val slot3 = favorites.getOrNull(2)

                fun bindSlot(viewId: Int, reqCode: Int, name: String, lat: Double, lon: Double) {
                    views.setTextViewText(viewId, name)
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

                if (slot1 != null) {
                    bindSlot(R.id.btnFavSlot1, 310, slot1.name, slot1.latitude, slot1.longitude)
                } else {
                    bindSlot(R.id.btnFavSlot1, 310, "Paris", 48.8566, 2.3522)
                }

                if (slot2 != null) {
                    bindSlot(R.id.btnFavSlot2, 311, slot2.name, slot2.latitude, slot2.longitude)
                } else {
                    bindSlot(R.id.btnFavSlot2, 311, "Tokyo", 35.6762, 139.6503)
                }

                if (slot3 != null) {
                    bindSlot(R.id.btnFavSlot3, 312, slot3.name, slot3.latitude, slot3.longitude)
                } else {
                    bindSlot(R.id.btnFavSlot3, 312, "New York", 40.7128, -74.0060)
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
