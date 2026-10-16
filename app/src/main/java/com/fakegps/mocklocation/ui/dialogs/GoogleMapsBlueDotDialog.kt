package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
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
            .setTitle("🔵 Keep Google Maps Blue in Background")
            .setMessage(
                "If Google Maps turns ash or grey when Nowhere is in the background:\n\n" +
                "1. Location Permission: Must be set to 'Allow all the time' so Android keeps sending GPS fixes when you switch to Maps.\n\n" +
                "2. Battery: Set Nowhere to 'Unrestricted' so the system does not pause GPS injection.\n\n" +
                "3. Google Location Accuracy: Turn OFF Wi-Fi scanning to prevent real-world network location conflicts."
            )
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> com.fakegps.mocklocation.util.PermissionHelper.openAppNotificationSettings(activity)
                    1 -> com.fakegps.mocklocation.util.PermissionHelper.requestIgnoreBatteryOptimizations(activity)
                    2 -> com.fakegps.mocklocation.util.PermissionHelper.openLocationScanningSettings(activity)
                    3 -> com.fakegps.mocklocation.util.PermissionHelper.openGoogleMapsAppInfo(activity)
                    4 -> oemDetector.openOEMSpecificSettings(activity)
                }
            }
            .setPositiveButton("Set Permission") { _, _ ->
                com.fakegps.mocklocation.util.PermissionHelper.openAppNotificationSettings(activity)
            }
            .setNeutralButton("Battery: Unrestricted") { _, _ ->
                com.fakegps.mocklocation.util.PermissionHelper.requestIgnoreBatteryOptimizations(activity)
            }
            .setNegativeButton("Got It", null)
            .show()
    }
}
