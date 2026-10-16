package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.os.Build
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GoogleMapsBlueDotDialog(
    private val activity: Activity
) {

    fun show() {
        val oemDetector = com.fakegps.mocklocation.util.OEMDetector
        val isOEM = oemDetector.isAggressiveOEM()
        val oemName = oemDetector.getDeviceOEM().name.replace("_", " ")

        val options = mutableListOf(
            "📍  Location Permission -> Select 'Allow all the time'",
            "⚡  Battery Usage -> Select 'Unrestricted'",
            "📶  Turn OFF 'Google Location Accuracy' & Wi-Fi Scanning",
            "🗺️  Force Stop Google Maps (Clean Reconnect)"
        )
        if (isOEM) {
            options.add("🛡️  Open $oemName Auto-start / Background Settings")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("🔵 Keep Google Maps Blue Without Widgets")
            .setMessage(
                "To keep Google Maps 100% solid blue in the background without needing a home screen widget:\n\n" +
                "1. Location Permission: Must be set to 'Allow all the time' so Android keeps streaming mock coordinates when Nowhere is minimized.\n\n" +
                "2. Battery: Set Nowhere to 'Unrestricted' so the system never freezes the background GPS engine.\n\n" +
                "3. Google Location Accuracy: Turn OFF Wi-Fi scanning to prevent real-world network conflicts."
            )
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PermissionHelper.hasBackgroundLocationPermission(activity)) {
                            PermissionHelper.requestBackgroundLocationPermission(activity)
                        } else {
                            PermissionHelper.openAppLocationSettings(activity)
                        }
                    }
                    1 -> com.fakegps.mocklocation.util.PermissionHelper.requestIgnoreBatteryOptimizations(activity)
                    2 -> com.fakegps.mocklocation.util.PermissionHelper.openLocationScanningSettings(activity)
                    3 -> com.fakegps.mocklocation.util.PermissionHelper.openGoogleMapsAppInfo(activity)
                    4 -> oemDetector.openOEMSpecificSettings(activity)
                }
            }
            .setPositiveButton("Set Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PermissionHelper.hasBackgroundLocationPermission(activity)) {
                    PermissionHelper.requestBackgroundLocationPermission(activity)
                } else {
                    PermissionHelper.openAppLocationSettings(activity)
                }
            }
            .setNeutralButton("Battery: Unrestricted") { _, _ ->
                com.fakegps.mocklocation.util.PermissionHelper.requestIgnoreBatteryOptimizations(activity)
            }
            .setNegativeButton("Got It", null)
            .show()
    }
}
