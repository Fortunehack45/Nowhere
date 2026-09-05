package com.fakegps.mocklocation.automation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.automation.data.AutomationSettingsEntity
import com.fakegps.mocklocation.automation.engine.ScheduleExecutor
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.util.ThemeColorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NowhereAutomationWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE_AUTOMATION_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_AUTOMATION_WIDGET"
        const val ACTION_WIDGET_TOGGLE_SCHEDULES = "com.fakegps.mocklocation.ACTION_WIDGET_TOGGLE_SCHEDULES"
        const val ACTION_WIDGET_TOGGLE_WIFI = "com.fakegps.mocklocation.ACTION_WIDGET_TOGGLE_WIFI"
        const val ACTION_WIDGET_TOGGLE_MOTION = "com.fakegps.mocklocation.ACTION_WIDGET_TOGGLE_MOTION"
        const val ACTION_WIDGET_OPEN_AUTOMATION = "com.fakegps.mocklocation.ACTION_WIDGET_OPEN_AUTOMATION"

        fun updateAllAutomationWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereAutomationWidgetProvider::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateWidgetDirect(context, appWidgetManager, id)
                    }
                }
            } catch (e: Exception) {
                // Non-fatal widget update fallback
            }
        }

        private fun buildAutomationRemoteViews(
            context: Context,
            isDark: Boolean,
            schedulesEnabled: Boolean,
            wifiEnabled: Boolean,
            motionEnabled: Boolean,
            nextScheduleText: String
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_automation_layout)
            val primaryColor = ThemeColorManager.getPrimaryColor(context)

            // Dynamic Dark / Light theme styling
            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.automationWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setInt(R.id.cardWidgetAutomationBody, "setBackgroundResource", bgButtonRes)
            views.setInt(R.id.btnWidgetToggleSchedules, "setBackgroundResource", bgButtonRes)
            views.setInt(R.id.btnWidgetToggleWifi, "setBackgroundResource", bgButtonRes)
            views.setInt(R.id.btnWidgetToggleMotion, "setBackgroundResource", bgButtonRes)
            views.setInt(R.id.btnWidgetOpenAutomation, "setBackgroundResource", bgButtonRes)

            views.setTextColor(R.id.tvWidgetAutomationTitle, primaryColor)
            views.setTextColor(R.id.tvWidgetOpenAutomationText, primaryColor)
            views.setInt(R.id.ivWidgetAutomationLogo, "setColorFilter", primaryColor)

            val anyActive = schedulesEnabled || wifiEnabled || motionEnabled
            if (anyActive) {
                views.setTextViewText(R.id.tvWidgetAutomationStatus, "ACTIVE")
                views.setTextColor(R.id.tvWidgetAutomationStatus, primaryColor)
            } else {
                views.setTextViewText(R.id.tvWidgetAutomationStatus, "STANDBY")
                views.setTextColor(R.id.tvWidgetAutomationStatus, secondaryText)
            }

            views.setTextViewText(R.id.tvWidgetNextScheduleTime, if (schedulesEnabled) nextScheduleText else "Schedules Off")
            views.setTextColor(R.id.tvWidgetNextScheduleTime, if (schedulesEnabled) primaryText else secondaryText)

            views.setTextViewText(R.id.tvWidgetWifiStatus, if (wifiEnabled) "Active Monitoring" else "Disabled")
            views.setTextColor(R.id.tvWidgetWifiStatus, if (wifiEnabled) primaryText else secondaryText)

            views.setTextViewText(R.id.tvWidgetMotionSyncStatus, if (motionEnabled) "Active" else "Off")
            views.setTextColor(R.id.tvWidgetMotionSyncStatus, if (motionEnabled) primaryText else secondaryText)

            // Button highlight states
            views.setTextColor(R.id.tvWidgetToggleSchedulesText, if (schedulesEnabled) primaryColor else primaryText)
            views.setTextColor(R.id.tvWidgetToggleWifiText, if (wifiEnabled) primaryColor else primaryText)
            views.setTextColor(R.id.tvWidgetToggleMotionText, if (motionEnabled) primaryColor else primaryText)

            // Pending Intents
            fun makeActionPendingIntent(actionStr: String, reqCode: Int): PendingIntent {
                val intent = Intent(context, NowhereAutomationWidgetProvider::class.java).apply {
                    action = actionStr
                    setPackage(context.packageName)
                }
                return PendingIntent.getBroadcast(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            views.setOnClickPendingIntent(R.id.btnWidgetToggleSchedules, makeActionPendingIntent(ACTION_WIDGET_TOGGLE_SCHEDULES, 8101))
            views.setOnClickPendingIntent(R.id.btnWidgetToggleWifi, makeActionPendingIntent(ACTION_WIDGET_TOGGLE_WIFI, 8102))
            views.setOnClickPendingIntent(R.id.btnWidgetToggleMotion, makeActionPendingIntent(ACTION_WIDGET_TOGGLE_MOTION, 8103))

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("extra_open_automation", true)
                setPackage(context.packageName)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                8104,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetOpenAutomation, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.automationWidgetRoot, openAppPendingIntent)

            return views
        }

        fun updateWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getInstance(context)
                val settings = db.automationSettingsDao().getSettings() ?: AutomationSettingsEntity()

                val schedulesEnabled = settings.scheduledAutomationEnabled
                val wifiEnabled = settings.wifiTriggersEnabled
                val motionEnabled = settings.motionSyncEnabled

                // Find next upcoming schedule
                val now = System.currentTimeMillis()
                val cursor = db.openHelper.readableDatabase.query(
                    "SELECT nextTriggerAt, name FROM automation_schedules WHERE enabled = 1 AND nextTriggerAt > $now ORDER BY nextTriggerAt ASC LIMIT 1"
                )
                var nextScheduleText = "None scheduled"
                if (cursor.moveToFirst()) {
                    val nextTime = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val sdf = SimpleDateFormat("h:mm a", Locale.US)
                    nextScheduleText = "${sdf.format(Date(nextTime))} ($name)"
                }
                cursor.close()

                val prefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(context)
                val finalViews = when (prefs.appTheme) {
                    "LIGHT" -> buildAutomationRemoteViews(context, isDark = false, schedulesEnabled, wifiEnabled, motionEnabled, nextScheduleText)
                    "DARK" -> buildAutomationRemoteViews(context, isDark = true, schedulesEnabled, wifiEnabled, motionEnabled, nextScheduleText)
                    else -> {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            RemoteViews(
                                buildAutomationRemoteViews(context, isDark = false, schedulesEnabled, wifiEnabled, motionEnabled, nextScheduleText),
                                buildAutomationRemoteViews(context, isDark = true, schedulesEnabled, wifiEnabled, motionEnabled, nextScheduleText)
                            )
                        } else {
                            val isDark = ThemeColorManager.isWidgetDarkMode(context)
                            buildAutomationRemoteViews(context, isDark, schedulesEnabled, wifiEnabled, motionEnabled, nextScheduleText)
                        }
                    }
                }

                appWidgetManager.updateAppWidget(appWidgetId, finalViews)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidgetDirect(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_UPDATE_AUTOMATION_WIDGET -> {
                updateAllAutomationWidgets(context)
            }
            ACTION_WIDGET_TOGGLE_SCHEDULES -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getInstance(context)
                    val settings = db.automationSettingsDao().getSettings() ?: AutomationSettingsEntity()
                    val newEnabled = !settings.scheduledAutomationEnabled
                    db.automationSettingsDao().setScheduledAutomationEnabled(newEnabled)
                    if (newEnabled) {
                        ScheduleExecutor.scheduleAllEnabled(context)
                    } else {
                        ScheduleExecutor.cancelAll(context)
                    }
                    updateAllAutomationWidgets(context)
                }
            }
            ACTION_WIDGET_TOGGLE_WIFI -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getInstance(context)
                    val settings = db.automationSettingsDao().getSettings() ?: AutomationSettingsEntity()
                    val newEnabled = !settings.wifiTriggersEnabled
                    db.automationSettingsDao().setWifiTriggersEnabled(newEnabled)
                    updateAllAutomationWidgets(context)
                }
            }
            ACTION_WIDGET_TOGGLE_MOTION -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getInstance(context)
                    val settings = db.automationSettingsDao().getSettings() ?: AutomationSettingsEntity()
                    val newEnabled = !settings.motionSyncEnabled
                    db.automationSettingsDao().setMotionSyncEnabled(newEnabled)
                    val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                        action = if (newEnabled) MockLocationService.ACTION_START_MOTION_SYNC else MockLocationService.ACTION_STOP_MOTION_SYNC
                    }
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (ignored: Exception) {}
                    updateAllAutomationWidgets(context)
                }
            }
        }
    }
}
