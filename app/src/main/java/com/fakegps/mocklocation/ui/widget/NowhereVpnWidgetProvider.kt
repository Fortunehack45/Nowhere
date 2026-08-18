package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.vpn.IpManager
import com.fakegps.mocklocation.vpn.NowhereVpnService

class NowhereVpnWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "NowhereVpnWidget"
        const val ACTION_VPN_WIDGET_TOGGLE = "com.fakegps.mocklocation.ACTION_VPN_WIDGET_TOGGLE"
        const val ACTION_UPDATE_VPN_WIDGET = "com.fakegps.mocklocation.ACTION_UPDATE_VPN_WIDGET"

        fun updateAllVpnWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, NowhereVpnWidgetProvider::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateVpnWidgetDirect(context, appWidgetManager, id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct VPN widget update: ${e.message}")
            }
        }

        fun updateVpnWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_vpn_layout)
            val sessionPrefs = SessionPreferences(context)
            val isRunning = NowhereVpnService.isRunning
            val node = IpManager.findNodeById(sessionPrefs.activeIpNodeId) ?: IpManager.GLOBAL_NODES.first()

            val stats = NowhereVpnService.trafficStats.value
            if (isRunning) {
                views.setTextViewText(R.id.tvWidgetVpnStatus, "ACTIVE")
                views.setTextColor(R.id.tvWidgetVpnStatus, ContextCompat.getColor(context, R.color.badge_active_text))
                views.setTextViewText(R.id.btnWidgetVpnToggle, "Disconnect Shield")
                views.setTextViewText(R.id.tvWidgetVpnNode, "${node.flagEmoji} ${node.city}, ${node.country}")
                views.setTextViewText(R.id.tvWidgetVpnIp, "Virtual IP: ${node.virtualIp} • Protected")
                views.setTextViewText(R.id.tvWidgetVpnData, "↓ ${stats.formatDownload()}  ↑ ${stats.formatUpload()} (${stats.formatDuration()})")
            } else {
                views.setTextViewText(R.id.tvWidgetVpnStatus, "DIRECT")
                views.setTextColor(R.id.tvWidgetVpnStatus, ContextCompat.getColor(context, R.color.text_muted))
                views.setTextViewText(R.id.btnWidgetVpnToggle, "Activate Shield")
                views.setTextViewText(R.id.tvWidgetVpnNode, "${node.flagEmoji} ${node.name} (Ready)")
                views.setTextViewText(R.id.tvWidgetVpnIp, "Direct Connection • Tap to Mask IP")
                views.setTextViewText(R.id.tvWidgetVpnData, "↓ 0.00 KB  ↑ 0.00 KB (Standby)")
            }

            // Open App Intent
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_VPN_DIALOG", true)
                setPackage(context.packageName)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                201,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.vpnWidgetRoot, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.btnWidgetVpnNodes, openAppPendingIntent)

            // Toggle Action Intent
            val toggleIntent = Intent(context, NowhereVpnWidgetProvider::class.java).apply {
                action = ACTION_VPN_WIDGET_TOGGLE
                setPackage(context.packageName)
            }
            views.setOnClickPendingIntent(
                R.id.btnWidgetVpnToggle,
                PendingIntent.getBroadcast(context, 202, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateVpnWidgetDirect(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_VPN_WIDGET_TOGGLE -> {
                val isRunning = NowhereVpnService.isRunning
                if (isRunning) {
                    NowhereVpnService.stopVpn(context)
                } else {
                    val sessionPrefs = SessionPreferences(context)
                    val node = IpManager.findNodeById(sessionPrefs.activeIpNodeId) ?: IpManager.GLOBAL_NODES.first()
                    NowhereVpnService.startVpn(context, node)
                }
                updateAllVpnWidgets(context)
            }
            ACTION_UPDATE_VPN_WIDGET, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> {
                updateAllVpnWidgets(context)
            }
        }
    }
}
