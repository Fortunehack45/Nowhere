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

        binding.btnLayer3dVector.setOnClickListener {
            selectLayer("3D_VECTOR")
        }
    }

    private fun selectLayer(sourceKey: String) {
        settingsPrefs.mapTileSource = sourceKey
        updateSelectionUi(sourceKey)
        onLayerSelected(sourceKey)
        dismiss()
    }

    private fun updateSelectionUi(currentSource: String) {
        binding.ivCheckStandard.visibility = if (currentSource == "MAPNIK") View.VISIBLE else View.GONE
        binding.btnLayerStandard.setBackgroundResource(
            if (currentSource == "MAPNIK") R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card_unselected
        )

        val isSat = currentSource == "SATELLITE" || currentSource == "ESRI_SAT" || currentSource == "USGS_SAT"
        binding.ivCheckSatellite.visibility = if (isSat) View.VISIBLE else View.GONE
        binding.btnLayerSatellite.setBackgroundResource(
            if (isSat) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card_unselected
        )

        val isTopo = currentSource == "TOPO"
        binding.ivCheckTopo.visibility = if (isTopo) View.VISIBLE else View.GONE
        binding.btnLayerTopo.setBackgroundResource(
            if (isTopo) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card_unselected
        )

        val is3D = currentSource == "3D_VECTOR" || currentSource == "MAPLIBRE_3D" || currentSource == "CARTO_3D"
        binding.ivCheck3dVector.visibility = if (is3D) View.VISIBLE else View.GONE
        binding.btnLayer3dVector.setBackgroundResource(
            if (is3D) R.drawable.bg_plan_card_selected else R.drawable.bg_plan_card_unselected
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MapLayersBottomSheet"
    }
}
