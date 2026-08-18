package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.SessionTimerManager
import com.fakegps.mocklocation.ui.MainActivity

class NowhereSessionTimerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE_SESSION_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_SESSION_WIDGET"

        fun updateAllSessionWidgets(context: Context) {
            val intent = Intent(context, NowhereSessionTimerWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_SESSION_WIDGET
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateSessionWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_UPDATE_SESSION_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereSessionTimerWidgetProvider::class.java))
                for (id in ids) {
                    updateSessionWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    private fun updateSessionWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_nowhere_session_timer_layout)
        val sessionPrefs = SessionPreferences(context)
        val isRunning = sessionPrefs.isSessionActive && !sessionPrefs.isSessionExpired
        val isExpired = sessionPrefs.isSessionExpired

        if (isRunning) {
            views.setTextViewText(R.id.tvWidgetSessionStatus, "ACTIVE")
            views.setTextColor(R.id.tvWidgetSessionStatus, ContextCompat.getColor(context, R.color.badge_active_text))
            views.setTextViewText(R.id.tvWidgetTimeRemaining, sessionPrefs.formatRemainingTime())
            views.setTextViewText(R.id.tvWidgetTotalAllocated, "Total: ${sessionPrefs.formatAllocatedDuration()}")
        } else if (isExpired) {
            views.setTextViewText(R.id.tvWidgetSessionStatus, "EXPIRED")
            views.setTextColor(R.id.tvWidgetSessionStatus, ContextCompat.getColor(context, R.color.badge_error_text))
            views.setTextViewText(R.id.tvWidgetTimeRemaining, "00:00:00")
            views.setTextViewText(R.id.tvWidgetTotalAllocated, "Tap +1h to resume")
        } else {
            views.setTextViewText(R.id.tvWidgetSessionStatus, "STANDBY")
            views.setTextColor(R.id.tvWidgetSessionStatus, ContextCompat.getColor(context, R.color.text_muted))
            views.setTextViewText(R.id.tvWidgetTimeRemaining, "02:00:00")
            views.setTextViewText(R.id.tvWidgetTotalAllocated, "Ready to start (2h)")
        }

        // Open App with Extend Dialog Intent
        val extendIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SESSION_EXTEND_DIALOG", true)
            setPackage(context.packageName)
        }
        val extendPendingIntent = PendingIntent.getActivity(
            context,
            401,
            extendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetExtendOneHour, extendPendingIntent)

        // Open App Intent
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            402,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.sessionTimerWidgetRoot, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.btnWidgetOpenApp, openAppPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
