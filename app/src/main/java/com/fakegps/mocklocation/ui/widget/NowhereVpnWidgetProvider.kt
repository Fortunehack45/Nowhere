package com.fakegps.mocklocation.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.vpn.KillSwitchManager
import com.fakegps.mocklocation.vpn.KillSwitchSinkholeService

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

        private fun buildVpnRemoteViews(context: Context, isDark: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nowhere_vpn_layout)
            val sessionPrefs = SessionPreferences(context)
            val isEnabled = sessionPrefs.isKillSwitchEnabled
            val isSinkhole = KillSwitchSinkholeService.isSinkholeActive
            val isMockActive = sessionPrefs.isSessionActive

            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            views.setInt(R.id.ivWidgetVpnLogo, "setColorFilter", primaryColor)
            views.setTextColor(R.id.tvWidgetVpnTitle, primaryColor)
            views.setTextColor(R.id.tvWidgetVpnData, primaryColor)
            views.setInt(R.id.ivWidgetVpnToggleBg, "setColorFilter", primaryColor)
            views.setTextColor(R.id.btnWidgetVpnNodes, primaryColor)

            // Dynamic Dark / Light theme styling
            val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
            val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
            val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

            views.setInt(R.id.vpnWidgetRoot, "setBackgroundResource", bgGlassRes)
            views.setInt(R.id.btnWidgetVpnNodes, "setBackgroundResource", bgButtonRes)
            views.setTextColor(R.id.tvWidgetVpnNode, primaryText)
            views.setTextColor(R.id.tvWidgetVpnIp, secondaryText)
            views.setTextViewText(R.id.tvWidgetVpnTitle, "KILL SWITCH")
            views.setTextViewText(R.id.btnWidgetVpnNodes, "Manage")

            if (isEnabled) {
                if (isSinkhole) {
                    views.setTextViewText(R.id.tvWidgetVpnStatus, "LEAK HALTED")
                    views.setTextColor(R.id.tvWidgetVpnStatus, android.graphics.Color.parseColor("#FF3B30"))
                    views.setTextViewText(R.id.btnWidgetVpnToggle, "Bypass Shield")
                    views.setTextViewText(R.id.tvWidgetVpnNode, "🛡️ Internet Halted")
                    views.setTextViewText(R.id.tvWidgetVpnIp, "Mock GPS Inactive • Real Location Protected")
                    views.setTextViewText(R.id.tvWidgetVpnData, "OS Sinkhole Active (0 B/s leak)")
                } else if (isMockActive) {
                    views.setTextViewText(R.id.tvWidgetVpnStatus, "ARMED")
                    views.setTextColor(R.id.tvWidgetVpnStatus, android.graphics.Color.parseColor("#30D158"))
                    views.setTextViewText(R.id.btnWidgetVpnToggle, "Disarm Shield")
                    views.setTextViewText(R.id.tvWidgetVpnNode, "🛡️ Shield Armed & Active")
                    views.setTextViewText(R.id.tvWidgetVpnIp, "Mock Location Injecting • Leaks Blocked")
                    views.setTextViewText(R.id.tvWidgetVpnData, "Fail-safe: Cuts internet if GPS stops")
                } else if (sessionPrefs.isKillSwitchBypassed) {
                    views.setTextViewText(R.id.tvWidgetVpnStatus, "BYPASS")
                    views.setTextColor(R.id.tvWidgetVpnStatus, android.graphics.Color.parseColor("#FF9500"))
                    views.setTextViewText(R.id.btnWidgetVpnToggle, "Re-Arm Shield")
                    views.setTextViewText(R.id.tvWidgetVpnNode, "⚠️ Emergency Bypass Active")
                    views.setTextViewText(R.id.tvWidgetVpnIp, "Normal Internet Flow Restored")
                    views.setTextViewText(R.id.tvWidgetVpnData, "Tap to re-engage leak shield")
                } else {
                    views.setTextViewText(R.id.tvWidgetVpnStatus, "LEAK HALTED")
                    views.setTextColor(R.id.tvWidgetVpnStatus, android.graphics.Color.parseColor("#FF3B30"))
                    views.setTextViewText(R.id.btnWidgetVpnToggle, "Disarm Shield")
                    views.setTextViewText(R.id.tvWidgetVpnNode, "🛡️ Protection Active")
                    views.setTextViewText(R.id.tvWidgetVpnIp, "Ready for Mock GPS simulation")
                    views.setTextViewText(R.id.tvWidgetVpnData, "OS Sinkhole Standby")
                }
            } else {
                views.setTextViewText(R.id.tvWidgetVpnStatus, "DISABLED")
                views.setTextColor(R.id.tvWidgetVpnStatus, secondaryText)
                views.setTextViewText(R.id.btnWidgetVpnToggle, "Arm Shield")
                views.setTextViewText(R.id.tvWidgetVpnNode, "🛡️ Kill Switch Standby")
                views.setTextViewText(R.id.tvWidgetVpnIp, "Tap to enforce OS-level leak protection")
                views.setTextViewText(R.id.tvWidgetVpnData, "Protection: Inactive")
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
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                202,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.layoutWidgetVpnToggle, togglePendingIntent)
            views.setOnClickPendingIntent(R.id.btnWidgetVpnToggle, togglePendingIntent)

            return views
        }

        fun updateVpnWidgetDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = com.fakegps.mocklocation.data.preferences.AppSettingsPreferences(context)
            val views = when (prefs.appTheme) {
                "LIGHT" -> buildVpnRemoteViews(context, isDark = false)
                "DARK" -> buildVpnRemoteViews(context, isDark = true)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(buildVpnRemoteViews(context, isDark = false), buildVpnRemoteViews(context, isDark = true))
                    } else {
                        val isDark = com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(context)
                        buildVpnRemoteViews(context, isDark)
                    }
                }
            }
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
                val sessionPrefs = SessionPreferences(context)
                if (sessionPrefs.isKillSwitchEnabled) {
                    if (KillSwitchSinkholeService.isSinkholeActive && !sessionPrefs.isKillSwitchBypassed) {
                        KillSwitchManager.setBypassed(context, true)
                        Toast.makeText(context, "Kill Switch: Emergency Bypass Active", Toast.LENGTH_SHORT).show()
                    } else {
                        KillSwitchManager.setEnabled(context, false)
                        Toast.makeText(context, "Kill Switch: Disabled", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val vpnIntent = VpnService.prepare(context)
                    if (vpnIntent != null) {
                        val openAppIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("OPEN_VPN_DIALOG", true)
                        }
                        context.startActivity(openAppIntent)
                        Toast.makeText(context, "Please grant VPN permission in Nowhere", Toast.LENGTH_SHORT).show()
                    } else {
                        KillSwitchManager.setEnabled(context, true)
                        Toast.makeText(context, "Kill Switch: Armed & Active", Toast.LENGTH_SHORT).show()
                    }
                }
                updateAllVpnWidgets(context)
            }
            ACTION_UPDATE_VPN_WIDGET -> {
                updateAllVpnWidgets(context)
            }
        }
    }
}
