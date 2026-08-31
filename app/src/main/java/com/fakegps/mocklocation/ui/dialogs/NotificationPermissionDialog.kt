package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogNotificationPermissionBinding
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NotificationPermissionDialog(
    private val activity: Activity,
    private val onRequestPermission: () -> Unit,
    private val onDismiss: (() -> Unit)? = null
) {

    fun show() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || PermissionHelper.hasNotificationPermission(activity)) {
            onDismiss?.invoke()
            return
        }

        val binding = DialogNotificationPermissionBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.btnGrantNotificationPermission.setOnClickListener {
            dialog.dismiss()
            onRequestPermission()
        }

        binding.btnDismissNotificationPermission.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.setOnDismissListener {
            onDismiss?.invoke()
        }

        dialog.show()
    }
}
