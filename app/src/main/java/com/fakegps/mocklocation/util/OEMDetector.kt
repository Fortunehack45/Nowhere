package com.fakegps.mocklocation.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object OEMDetector {

    enum class OEM {
        XIAOMI, SAMSUNG, HUAWEI, OPPO_ONEPLUS, VIVO, TRANSSION, OTHER
    }

    fun getDeviceOEM(): OEM {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> OEM.XIAOMI
            manufacturer.contains("samsung") -> OEM.SAMSUNG
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> OEM.HUAWEI
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> OEM.OPPO_ONEPLUS
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> OEM.VIVO
            manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") || manufacturer.contains("transsion") -> OEM.TRANSSION
            else -> OEM.OTHER
        }
    }

    fun isAggressiveOEM(): Boolean {
        return when (getDeviceOEM()) {
            OEM.XIAOMI, OEM.SAMSUNG, OEM.HUAWEI, OEM.OPPO_ONEPLUS, OEM.VIVO, OEM.TRANSSION -> true
            OEM.OTHER -> false
        }
    }

    fun getOEMGuidanceMessage(): String {
        return when (getDeviceOEM()) {
            OEM.XIAOMI -> "Xiaomi / HyperOS / MIUI may kill background apps on 'Clear All'. Please enable Autostart, set Battery Saver to 'No Restrictions', and Lock Nowhere in Recent Apps (swipe card down to show padlock 🔒)."
            OEM.SAMSUNG -> "Samsung OneUI may sleep background GPS or kill it on 'Clear All'. Please add Nowhere to 'Never Sleeping Apps' in Device Care, and lock Nowhere in Recent Apps (tap app icon in recents > 'Lock this app' 🔒)."
            OEM.HUAWEI -> "Huawei EMUI may aggressively stop background services. Go to Battery > App Launch and set Nowhere to 'Manage Manually' (Auto-launch, Secondary launch, and Run in background enabled)."
            OEM.OPPO_ONEPLUS -> "ColorOS/OxygenOS may kill background services on 'Clear All'. Please allow background activity in Battery settings and lock Nowhere in Recent Apps (tap 3-dots > Lock 🔒)."
            OEM.VIVO -> "Vivo FuntouchOS may terminate background location apps. Please allow 'High Background Power Consumption' and lock Nowhere in Recent Apps (swipe card down > Lock 🔒)."
            OEM.TRANSSION -> "Tecno / Infinix / itel (HiOS / XOS) aggressively stops background GPS. Go to Phone Master > Auto-start Management and allow Nowhere, then set Battery to 'Unrestricted' and lock Nowhere in Recent Apps (padlock 🔒)."
            OEM.OTHER -> "Please ensure battery optimization is disabled so location spoofing continues reliably when Nowhere is backgrounded or cleared from recents."
        }
    }

    fun openOEMSpecificSettings(context: Context): Boolean {
        val intents = when (getDeviceOEM()) {
            OEM.XIAOMI -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            )
            OEM.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"))
            )
            OEM.HUAWEI -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
            )
            OEM.OPPO_ONEPLUS -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            )
            OEM.VIVO -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            )
            OEM.TRANSSION -> listOf(
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartActivity")),
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.activity.AutoStartActivity")),
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.MainActivity")),
                Intent().setComponent(ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.AutoStartActivity"))
            )
            else -> emptyList()
        }

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {
            }
        }

        // Fallback to Application Details Settings if specific OEM component intent is not found
        return openAppDetailsSettings(context)
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (ignored: Exception) {
            false
        }
    }
}
