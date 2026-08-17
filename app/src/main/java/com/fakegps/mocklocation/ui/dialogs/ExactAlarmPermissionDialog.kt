package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.DialogExactAlarmPermissionBinding
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ExactAlarmPermissionDialog(
    private val activity: Activity,
    private val onDismiss: (() -> Unit)? = null
) {

    fun show() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onDismiss?.invoke()
            return
        }

        val binding = DialogExactAlarmPermissionBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        val sessionPrefs = SessionPreferences(activity)

        binding.btnGrantExactAlarm.setOnClickListener {
            sessionPrefs.hasPromptedExactAlarmPermission = true
            PermissionHelper.requestExactAlarmPermission(activity)
            dialog.dismiss()
            onDismiss?.invoke()
        }

        binding.btnDismissExactAlarm.setOnClickListener {
            sessionPrefs.hasPromptedExactAlarmPermission = true
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.setOnDismissListener {
            sessionPrefs.hasPromptedExactAlarmPermission = true
            onDismiss?.invoke()
        }

        dialog.show()
    }
}
