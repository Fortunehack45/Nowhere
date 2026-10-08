package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogLocationDisclosureBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Prominent Disclosure and User Consent Dialog for Location Data.
 * Fully compliant with Google Play Store User Data & Prominent Disclosure policy:
 * - Details specific data accessed (precise GPS and approximate network location coordinates).
 * - Details exact purpose of usage (map rendering, mock GPS calibration, route simulation).
 * - Discloses 100% on-device processing and guarantees zero tracking or sharing with third parties.
 * - Requires affirmative consent ("Agree & Continue") before any Android runtime location permission is triggered.
 */
class LocationDisclosureDialog(
    private val context: Context,
    private val onConsentGranted: () -> Unit,
    private val onConsentDenied: (() -> Unit)? = null
) {

    fun show() {
        val binding = DialogLocationDisclosureBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        val primaryColor = ThemeColorManager.getPrimaryColor(context)
        binding.btnDisclosureAgree.backgroundTintList = ColorStateList.valueOf(primaryColor)

        binding.btnDisclosureAgree.setOnClickListener {
            dialog.dismiss()
            onConsentGranted()
        }

        binding.btnDisclosureDeny.setOnClickListener {
            dialog.dismiss()
            onConsentDenied?.invoke()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
