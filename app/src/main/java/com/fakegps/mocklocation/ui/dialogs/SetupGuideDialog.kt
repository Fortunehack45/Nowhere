package com.fakegps.mocklocation.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogSetupGuideBinding
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SetupGuideDialog(
    private val context: Context,
    private val onOpenSettingsClicked: () -> Unit
) {

    fun show() {
        val binding = DialogSetupGuideBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.btnOpenDeveloperSettings.setOnClickListener {
            onOpenSettingsClicked()
            dialog.dismiss()
        }

        binding.btnDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
