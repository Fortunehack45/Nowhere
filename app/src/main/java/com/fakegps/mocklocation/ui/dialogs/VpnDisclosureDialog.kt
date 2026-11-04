package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogVpnDisclosureBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Prominent Disclosure and User Consent Dialog for VpnService API.
 * Fully compliant with Google Play Store VpnService Policy:
 * - Details specific core usage (WireGuard encrypted device tunnel to mask public IP matching mock location).
 * - Details zero data logging and strong ChaCha20-Poly1305 encryption.
 * - Confirms full user control and single-tap disconnection.
 * - Requires explicit affirmative consent ("Agree & Enable Ghost Shield") before VpnService.prepare() is initiated.
 */
class VpnDisclosureDialog(
    private val context: Context,
    private val onConsentGranted: () -> Unit,
    private val onConsentDenied: (() -> Unit)? = null
) {

    fun show() {
        val binding = DialogVpnDisclosureBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        val primaryColor = ThemeColorManager.getPrimaryColor(context)
        binding.btnVpnDisclosureAgree.backgroundTintList = ColorStateList.valueOf(primaryColor)

        binding.btnVpnDisclosureAgree.setOnClickListener {
            dialog.dismiss()
            onConsentGranted()
        }

        binding.btnVpnDisclosureDeny.setOnClickListener {
            dialog.dismiss()
            onConsentDenied?.invoke()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
