package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.weather.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NowhereWeatherWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE_WEATHER_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_WEATHER_WIDGET"

        fun updateAllWeatherWidgets(context: Context) {
            val intent = Intent(context, NowhereWeatherWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WEATHER_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        private fun buildWeatherRemoteViews(
            context: Context,
            isDark: Boolean,
            report: com.fakegps.mocklocation.weather.LocationWeatherReport?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_weather_layout)
            val settingsPrefs = AppSettingsPreferences(context)
            val useImperial = settingsPrefs.useImperialUnits

            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            views.setTextColor(R.id.tvWidgetWeatherTitle, primaryColor)
            views.setTextColor(R.id.tvWidgetWeatherTemp, primaryColor)
            views.setInt(R.id.ivWidgetWeatherDetailsBg, "setColorFilter", primaryColor)

            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.weatherWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setTextColor(R.id.tvWidgetWeatherLocation, primaryText)
            views.setTextColor(R.id.tvWidgetWeatherCondition, secondaryText)
            views.setTextColor(R.id.tvWidgetWeatherForecastSnippet, secondaryText)

            if (report != null) {
                val cur = report.current
                views.setTextViewText(R.id.tvWidgetWeatherEmoji, cur.conditionEmoji)
                views.setTextViewText(R.id.tvWidgetWeatherLocation, report.locationName)

                if (useImperial) {
                    views.setTextViewText(R.id.tvWidgetWeatherTemp, String.format("%.0f°F", cur.temperatureF))
                    views.setTextViewText(R.id.tvWidgetWeatherCondition, "${cur.conditionName} • Feels ${String.format("%.0f°F", (cur.apparentTemperatureC * 9.0 / 5.0) + 32.0)}")
                    if (report.forecast.isNotEmpty()) {
                        val f = report.forecast.first()
                        views.setTextViewText(R.id.tvWidgetWeatherForecastSnippet, "High: ${String.format("%.0f°F", f.maxTempF)} / Low: ${String.format("%.0f°F", f.minTempF)} • Humidity ${cur.humidityPercent}%")
                    }
                } else {
                    views.setTextViewText(R.id.tvWidgetWeatherTemp, String.format("%.0f°C", cur.temperatureC))
                    views.setTextViewText(R.id.tvWidgetWeatherCondition, "${cur.conditionName} • Feels ${String.format("%.0f°C", cur.apparentTemperatureC)}")
                    if (report.forecast.isNotEmpty()) {
                        val f = report.forecast.first()
                        views.setTextViewText(R.id.tvWidgetWeatherForecastSnippet, "High: ${String.format("%.0f°C", f.maxTempC)} / Low: ${String.format("%.0f°C", f.minTempC)} • Humidity ${cur.humidityPercent}%")
                    }
                }
            }

            // Open App Intent with Weather Sheet
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_WEATHER_DIALOG", true)
                setPackage(context.packageName)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                301,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.weatherWidgetRoot, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.btnWidgetWeatherDetails, openAppPendingIntent)

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWeatherWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_UPDATE_WEATHER_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereWeatherWidgetProvider::class.java))
                for (id in ids) {
                    updateWeatherWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateWeatherWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val sessionPrefs = SessionPreferences(context)
        val lat = sessionPrefs.lastLatitude
        val lon = sessionPrefs.lastLongitude

        fun applyViews(report: com.fakegps.mocklocation.weather.LocationWeatherReport?) {
            val prefs = AppSettingsPreferences(context)
            val finalViews = when (prefs.appTheme) {
                "LIGHT" -> buildWeatherRemoteViews(context, isDark = false, report)
                "DARK" -> buildWeatherRemoteViews(context, isDark = true, report)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(
                            buildWeatherRemoteViews(context, isDark = false, report),
                            buildWeatherRemoteViews(context, isDark = true, report)
                        )
                    } else {
                        val isDark = com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(context)
                        buildWeatherRemoteViews(context, isDark, report)
                    }
                }
            }
            appWidgetManager.updateAppWidget(appWidgetId, finalViews)
        }

        applyViews(null)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val report = WeatherManager.fetchWeather(context, lat, lon)
                applyViews(report)
            } catch (e: Exception) {
                // Keep initial views
            }
        }
    }
}
