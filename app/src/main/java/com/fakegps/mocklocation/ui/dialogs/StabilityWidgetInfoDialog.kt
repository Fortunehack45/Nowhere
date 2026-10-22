package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutDialogStabilityWidgetInfoBinding
import com.fakegps.mocklocation.ui.custom.applyLiquidTouch
import com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider
import com.fakegps.mocklocation.util.OEMDetector
import com.fakegps.mocklocation.util.PermissionHelper
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class StabilityWidgetInfoDialog(
    private val activity: Activity
) {

    fun show() {
        val binding = LayoutDialogStabilityWidgetInfoBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Apply active color theme dynamically so dialog matches user's chosen theme (e.g. Pink, Cyan, Emerald)
        val primaryColor = ThemeColorManager.getPrimaryColor(activity)
        val primaryCsl = ColorStateList.valueOf(primaryColor)

        binding.ivHeaderIcon.imageTintList = primaryCsl
        binding.ivSparkleNotice.imageTintList = primaryCsl
        binding.btnPinWidget.backgroundTintList = primaryCsl

        ThemeColorManager.applyThemeRecursively(binding.root, activity)

        val oem = OEMDetector.getDeviceOEM()
        val isAggressiveOem = OEMDetector.isAggressiveOEM()
        val oemDisplayName = when (oem) {
            OEMDetector.OEM.TRANSSION -> "Infinix / Tecno"
            OEMDetector.OEM.XIAOMI -> "Xiaomi / MIUI"
            OEMDetector.OEM.SAMSUNG -> "Samsung OneUI"
            OEMDetector.OEM.HUAWEI -> "Huawei / EMUI"
            OEMDetector.OEM.OPPO_ONEPLUS -> "OPPO / OnePlus"
            OEMDetector.OEM.VIVO -> "Vivo"
            else -> oem.name
        }

        if (isAggressiveOem) {
            binding.tvOemNoticeHeader.text = "Why Google Maps turns ash on $oemDisplayName"
            binding.btnOemSettings.visibility = View.VISIBLE
            binding.btnOemSettings.text = activity.getString(R.string.stability_info_oem_settings, oemDisplayName)
            binding.btnOemSettings.applyLiquidTouch {
                OEMDetector.openOEMSpecificSettings(activity)
            }
        } else {
            binding.tvOemNoticeHeader.text = "Why Google Maps turns ash in background"
            binding.btnOemSettings.visibility = View.GONE
        }

        // Pin Widget Directly with 1 Tap (Liquid Button Touch)
        binding.btnPinWidget.applyLiquidTouch {
            val appWidgetManager = AppWidgetManager.getInstance(activity)
            val provider = ComponentName(activity, NowhereAppWidgetProvider::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                try {
                    appWidgetManager.requestPinAppWidget(provider, null, null)
                    Toast.makeText(activity, R.string.stability_widget_pinned_success, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    openWidgetGallery()
                }
            } else {
                openWidgetGallery()
            }
            dialog.dismiss()
        }

        // Open Widget Gallery (Liquid Button Touch)
        binding.btnBrowseGallery.applyLiquidTouch {
            openWidgetGallery()
            dialog.dismiss()
        }

        // Battery Unrestricted (Liquid Button Touch)
        binding.btnBatteryUnrestricted.applyLiquidTouch {
            PermissionHelper.requestIgnoreBatteryOptimizations(activity)
        }

        // Location Allow All The Time (Liquid Button Touch)
        binding.btnLocationAllTime.applyLiquidTouch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PermissionHelper.hasBackgroundLocationPermission(activity)) {
                PermissionHelper.requestBackgroundLocationPermission(activity)
            } else {
                PermissionHelper.openAppLocationSettings(activity)
            }
        }

        binding.btnDismiss.applyLiquidTouch {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openWidgetGallery() {
        if (activity is FragmentActivity) {
            WidgetGalleryBottomSheet().show(activity.supportFragmentManager, "widget_gallery")
        }
    }
}
