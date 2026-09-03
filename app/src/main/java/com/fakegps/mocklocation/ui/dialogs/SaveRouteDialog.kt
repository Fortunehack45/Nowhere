package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogSaveRouteBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SaveRouteDialog(
    private val context: Context,
    private val waypointsCount: Int,
    private val totalDistanceMeters: Double,
    private val onSave: (name: String) -> Unit
) {

    fun show() {
        val binding = DialogSaveRouteBinding.inflate(LayoutInflater.from(context))
        binding.tvRouteSummary.text = String.format("%d Waypoints • %.2f km", waypointsCount, totalDistanceMeters / 1000.0)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.btnSaveRouteConfirm.setOnClickListener {
            val name = binding.etRouteName.text?.toString()?.trim() ?: ""
            onSave(name)
            dialog.dismiss()
        }

        binding.btnSaveRouteCancel.setOnClickListener {
            dialog.dismiss()
        }

        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, context)
        dialog.show()
    }
}
