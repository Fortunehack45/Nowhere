package com.fakegps.mocklocation.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import com.fakegps.mocklocation.databinding.DialogSaveFavoriteBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SaveFavoriteDialog(
    private val context: Context,
    private val latitude: Double,
    private val longitude: Double,
    private val onSave: (name: String, tag: String) -> Unit
) {

    fun show() {
        val binding = DialogSaveFavoriteBinding.inflate(LayoutInflater.from(context))
        val primaryColor = ThemeColorManager.getPrimaryColor(context)
        val primaryCsl = ColorStateList.valueOf(primaryColor)

        binding.tvFavoriteCoords.text = String.format("%.5f, %.5f", latitude, longitude)
        binding.tvFavoriteCoords.setTextColor(primaryColor)
        binding.btnSaveConfirm.backgroundTintList = primaryCsl

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

        ThemeColorManager.applyThemeRecursively(binding.root, context)
        // Ensure dynamic theme color overrides any static button background tint
        binding.btnSaveConfirm.backgroundTintList = primaryCsl
        binding.tvFavoriteCoords.setTextColor(primaryColor)

        dialog.show()
    }
}
