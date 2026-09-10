package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.databinding.LayoutBottomSheetMapLayersBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MapLayersBottomSheet(
    private val onLayerSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetMapLayersBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsPrefs: AppSettingsPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetMapLayersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPrefs = AppSettingsPreferences(requireContext())
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())
        updateSelectionUi(settingsPrefs.mapTileSource)

        binding.btnLayerStandard.setOnClickListener {
            selectLayer("MAPNIK")
        }

        binding.btnLayerSatellite.setOnClickListener {
            selectLayer("SATELLITE")
        }

        binding.btnLayerTopo.setOnClickListener {
            selectLayer("TOPO")
        }
    }

    private fun selectLayer(sourceKey: String) {
        settingsPrefs.mapTileSource = sourceKey
        updateSelectionUi(sourceKey)
        onLayerSelected(sourceKey)
        dismiss()
    }

    private fun updateSelectionUi(currentSource: String) {
        val ctx = context ?: return
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(ctx)
        val primaryCsl = android.content.res.ColorStateList.valueOf(primaryColor)

        val isMapnik = currentSource == "MAPNIK"
        binding.ivCheckStandard.visibility = if (isMapnik) View.VISIBLE else View.GONE
        binding.ivCheckStandard.imageTintList = primaryCsl
        binding.btnLayerStandard.background = if (isMapnik) {
            com.fakegps.mocklocation.util.ThemeColorManager.createSelectedPlanCardDrawable(primaryColor, ctx)
        } else {
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_plan_card_unselected)
        }

        val isSat = currentSource == "SATELLITE" || currentSource == "ESRI_SAT" || currentSource == "USGS_SAT"
        binding.ivCheckSatellite.visibility = if (isSat) View.VISIBLE else View.GONE
        binding.ivCheckSatellite.imageTintList = primaryCsl
        binding.btnLayerSatellite.background = if (isSat) {
            com.fakegps.mocklocation.util.ThemeColorManager.createSelectedPlanCardDrawable(primaryColor, ctx)
        } else {
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_plan_card_unselected)
        }

        val isTopo = currentSource == "TOPO"
        binding.ivCheckTopo.visibility = if (isTopo) View.VISIBLE else View.GONE
        binding.ivCheckTopo.imageTintList = primaryCsl
        binding.btnLayerTopo.background = if (isTopo) {
            com.fakegps.mocklocation.util.ThemeColorManager.createSelectedPlanCardDrawable(primaryColor, ctx)
        } else {
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_plan_card_unselected)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MapLayersBottomSheet"
    }
}
