package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GoogleMapsBlueDotDialog(
    private val activity: Activity
) {

    fun show() {
        val options = arrayOf(
            "1️⃣  Location Settings (Turn OFF 'Google Location Accuracy' & Wi-Fi Scanning)",
            "2️⃣  Developer Options (Verify Nowhere is Mock App)",
            "3️⃣  Force Stop Google Maps (Clean Reconnect)"
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle("🔵 Google Maps Blue Dot Optimizer")
            .setMessage(
                "If Google Maps displays an ash or grey dot instead of a solid blue dot:\n\n" +
                "• Reason: Google Location Accuracy scans nearby Wi-Fi & cell towers in the background, conflicting with your mock GPS.\n\n" +
                "• Quick Fix: Turn OFF 'Google Location Accuracy' and 'Wi-Fi scanning', then force stop Google Maps once.\n\n" +
                "Tap any action below:"
            )
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> PermissionHelper.openLocationScanningSettings(activity)
                    1 -> PermissionHelper.openDeveloperSettings(activity)
                    2 -> PermissionHelper.openGoogleMapsAppInfo(activity)
                }
            }
            .setPositiveButton("Open Location Settings") { _, _ ->
                PermissionHelper.openLocationScanningSettings(activity)
            }
            .setNeutralButton("Force Stop Maps") { _, _ ->
                PermissionHelper.openGoogleMapsAppInfo(activity)
            }
            .setNegativeButton("Got It", null)
            .show()
    }
}
