package com.fakegps.mocklocation.automation.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.automation.data.WifiTriggerEntity
import com.fakegps.mocklocation.automation.engine.WifiTriggerHandler
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.data.db.SavedRoute
import com.fakegps.mocklocation.databinding.LayoutDialogAddEditWifiTriggerBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditWifiTriggerDialog : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAddEditWifiTriggerBinding? = null
    private val binding get() = _binding!!

    private var favoritesList: List<FavoriteLocation> = emptyList()
    private var routesList: List<SavedRoute> = emptyList()

    companion object {
        fun newInstance(): AddEditWifiTriggerDialog {
            return AddEditWifiTriggerDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogAddEditWifiTriggerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeColorManager.applyThemeRecursively(binding.root, requireContext())

        setupTargetRadioGroup()
        loadDestinations()

        binding.btnDetectCurrentWifi.setOnClickListener {
            detectCurrentSsid()
        }

        binding.btnSaveWifiTrigger.setOnClickListener {
            saveTrigger()
        }
    }

    private fun detectCurrentSsid() {
        try {
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rawSsid = wifiManager?.connectionInfo?.ssid
            val sanitized = rawSsid?.trim()?.removeSurrounding("\"")
            if (!sanitized.isNullOrEmpty() && sanitized != "<unknown ssid>") {
                binding.etWifiSsid.setText(sanitized)
            } else {
                Toast.makeText(requireContext(), "Could not detect active WiFi. Please enter SSID manually.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "WiFi detection error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTargetRadioGroup() {
        binding.rgWifiTargetType.setOnCheckedChangeListener { _, checkedId ->
            updateTargetSpinner(checkedId == R.id.rbWifiTargetLocation)
        }
    }

    private fun loadDestinations() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(requireContext())
            favoritesList = db.favoriteDao().getAllFavoritesList()

            val cursor = db.openHelper.readableDatabase.query("SELECT id, name, waypointsJson, waypointsCount, totalDistanceMeters, defaultSpeedKmh, isLooping, createdAt FROM saved_routes")
            val routes = mutableListOf<SavedRoute>()
            while (cursor.moveToNext()) {
                routes.add(
                    SavedRoute(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        waypointsJson = cursor.getString(2),
                        waypointsCount = cursor.getInt(3),
                        totalDistanceMeters = cursor.getDouble(4),
                        defaultSpeedKmh = cursor.getFloat(5),
                        isLooping = cursor.getInt(6) == 1,
                        createdAt = cursor.getLong(7)
                    )
                )
            }
            cursor.close()
            routesList = routes

            withContext(Dispatchers.Main) {
                updateTargetSpinner(binding.rbWifiTargetLocation.isChecked)
            }
        }
    }

    private fun updateTargetSpinner(isLocation: Boolean) {
        val names = if (isLocation) {
            if (favoritesList.isEmpty()) listOf("No favorites (add favorites on map)") else favoritesList.map { "${it.name} (${it.tag})" }
        } else {
            if (routesList.isEmpty()) listOf("No saved routes (create a route first)") else routesList.map { "${it.name} (${it.waypointsCount} pts)" }
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerWifiTargetItem.adapter = adapter
    }

    private fun saveTrigger() {
        val ssid = binding.etWifiSsid.text?.toString()?.trim()
        if (ssid.isNullOrEmpty()) {
            binding.tilWifiSsid.error = "SSID required"
            return
        }

        val triggerType = if (binding.rbOnConnect.isChecked) WifiTriggerHandler.TRIGGER_ON_CONNECT else WifiTriggerHandler.TRIGGER_ON_DISCONNECT
        val isLocation = binding.rbWifiTargetLocation.isChecked

        val targetId = if (isLocation) {
            val selectedIdx = binding.spinnerWifiTargetItem.selectedItemPosition
            favoritesList.getOrNull(selectedIdx)?.id ?: 0L
        } else {
            val selectedIdx = binding.spinnerWifiTargetItem.selectedItemPosition
            routesList.getOrNull(selectedIdx)?.id ?: 0L
        }

        if (targetId <= 0L) {
            Toast.makeText(requireContext(), "Please select a valid destination", Toast.LENGTH_SHORT).show()
            return
        }

        val targetType = if (isLocation) "SINGLE_LOCATION" else "ROUTE"
        val appContext = context?.applicationContext ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            val trigger = WifiTriggerEntity(
                ssid = ssid,
                triggerType = triggerType,
                targetType = targetType,
                targetId = targetId,
                enabled = true
            )
            db.wifiTriggerDao().insertWifiTrigger(trigger)

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
