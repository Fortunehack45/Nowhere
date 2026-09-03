package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogSaveFavoriteBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SaveFavoriteDialog(
    private val context: Context,
    private val latitude: Double,
    private val longitude: Double,
    private val onSave: (name: String, tag: String) -> Unit
) {

    fun show() {
        val binding = DialogSaveFavoriteBinding.inflate(LayoutInflater.from(context))
        binding.tvFavoriteCoords.text = String.format("%.5f, %.5f", latitude, longitude)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.btnSaveConfirm.setOnClickListener {
            val name = binding.etFavoriteName.text?.toString()?.trim() ?: ""
            val tag = binding.etFavoriteTag.text?.toString()?.trim() ?: "Default"
            onSave(name, tag)
            dialog.dismiss()
        }

        binding.btnSaveCancel.setOnClickListener {
            dialog.dismiss()
        }

        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, context)
        dialog.show()
    }
}
