package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.DialogBatteryOptimizationBinding
import com.fakegps.mocklocation.util.OEMDetector
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BatteryOptimizationDialog(
    private val activity: Activity
) {

    fun show() {
        val binding = DialogBatteryOptimizationBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        val oem = OEMDetector.getDeviceOEM()
        binding.tvOemTitle.text = "Device Optimization (${oem.name})"
        binding.tvOemDescription.text = OEMDetector.getOEMGuidanceMessage()

        val sessionPrefs = SessionPreferences(activity)
        val isAggressiveOem = when (oem) {
            OEMDetector.OEM.XIAOMI,
            OEMDetector.OEM.HUAWEI,
            OEMDetector.OEM.OPPO_ONEPLUS,
            OEMDetector.OEM.VIVO -> true
            else -> false
        }

        if (isAggressiveOem && !sessionPrefs.hasPromptedOemWidgetNudge) {
            val oemDisplayName = when (oem) {
                OEMDetector.OEM.XIAOMI -> "Xiaomi / MIUI"
                OEMDetector.OEM.HUAWEI -> "Huawei / EMUI"
                OEMDetector.OEM.OPPO_ONEPLUS -> "OPPO / OnePlus"
                OEMDetector.OEM.VIVO -> "Vivo"
                else -> oem.name
            }
            binding.layoutOemWidgetNudge.visibility = View.VISIBLE
            binding.tvWidgetNudgeTitle.text = "RECOMMENDED FOR $oemDisplayName"
            binding.tvWidgetNudgeDescription.text = "Add the Nowhere Quick Actions or Icon widget to your home screen — active widgets prevent $oemDisplayName battery managers from freezing or killing background location spoofing."

            binding.btnDismissWidgetNudge.setOnClickListener {
                sessionPrefs.hasPromptedOemWidgetNudge = true
                binding.layoutOemWidgetNudge.visibility = View.GONE
            }
        } else {
            binding.layoutOemWidgetNudge.visibility = View.GONE
        }

        binding.btnDisableOptimization.setOnClickListener {
            if (isAggressiveOem) {
                sessionPrefs.hasPromptedOemWidgetNudge = true
            }
            // First try OEM specific launcher
            val launched = OEMDetector.openOEMSpecificSettings(activity)
            if (!launched) {
                PermissionHelper.requestIgnoreBatteryOptimizations(activity)
            }
            dialog.dismiss()
        }

        binding.btnDismissBattery.setOnClickListener {
            if (isAggressiveOem) {
                sessionPrefs.hasPromptedOemWidgetNudge = true
            }
            dialog.dismiss()
        }

        dialog.show()
    }
}
