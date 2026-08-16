package com.fakegps.mocklocation.util

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.io.File

object PermissionHelper {

    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                activity.startActivity(intent)
            } catch (ignored: Exception) {
            }
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS)
                activity.startActivity(intent)
            }
        }
    }

    /**
     * Checks if the app is currently authorized as the Mock Location provider.
     * Combines AppOpsManager check with a safe probe on LocationManager.
     */
    fun isMockLocationEnabled(context: Context): Boolean {
        // AppOps check
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOpsManager != null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            if (mode == AppOpsManager.MODE_ALLOWED) {
                return true
            }
        }

        // Test provider probe verification
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        val probeProvider = "mock_probe_test"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = android.location.provider.ProviderProperties.Builder().build()
                locationManager.addTestProvider(probeProvider, properties, emptySet())
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    probeProvider, false, false, false, false, false, false, false, 1, 1
                )
            }
            locationManager.removeTestProvider(probeProvider)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/su", "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup"
        )
        if (paths.any { File(it).exists() }) return true
        return checkSuBinary()
    }

    private fun checkSuBinary(): Boolean {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        if (osName.contains("windows") || osName.contains("mac")) {
            return false
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = process.inputStream.bufferedReader()
            val output = reader.readLine()
            process.waitFor() == 0 && !output.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Attempts to automatically grant mock location appops and permissions via Root (su).
     */
    fun tryAutoGrantRootMockPermission(context: Context): Boolean {
        return try {
            val pkg = context.packageName
            val commands = arrayOf(
                "cmd appops set $pkg android:mock_location allow",
                "cmd appops set $pkg MOCK_LOCATION allow",
                "appops set $pkg android:mock_location allow",
                "pm grant $pkg android.permission.ACCESS_MOCK_LOCATION",
                "settings put secure mock_location 1"
            )
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            for (cmd in commands) {
                outputStream.writeBytes("$cmd\n")
            }
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            val exitCode = process.waitFor()
            exitCode == 0 && isMockLocationEnabled(context)
        } catch (e: Exception) {
            false
        }
    }

    fun openDeveloperSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            activity.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            activity.startActivity(intent)
        }
    }
}
