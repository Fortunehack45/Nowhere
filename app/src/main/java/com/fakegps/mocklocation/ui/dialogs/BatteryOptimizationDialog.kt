package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
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

        binding.btnDisableOptimization.setOnClickListener {
            // First try OEM specific launcher
            val launched = OEMDetector.openOEMSpecificSettings(activity)
            if (!launched) {
                PermissionHelper.requestIgnoreBatteryOptimizations(activity)
            }
            dialog.dismiss()
        }

        binding.btnDismissBattery.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
