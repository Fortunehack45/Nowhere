package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
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

        val isRooted = PermissionHelper.isDeviceRooted()
        binding.btnAutoGrantRoot.visibility = if (isRooted) View.VISIBLE else View.VISIBLE

        binding.btnAutoGrantRoot.setOnClickListener {
            val success = PermissionHelper.tryAutoGrantRootMockPermission(context)
            if (success) {
                Toast.makeText(context, "Root Mock Location Granted Successfully! 🎉", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Root grant unavailable. Please select Nowhere in Developer Options manually.", Toast.LENGTH_SHORT).show()
            }
        }

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
