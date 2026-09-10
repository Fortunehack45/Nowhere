package com.fakegps.mocklocation.ui.dialogs

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.LayoutAntiDetectionSheetBinding
import com.fakegps.mocklocation.engine.GhostCloakEngine
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AntiDetectionBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AntiDetectionBottomSheet"

        fun newInstance(): AntiDetectionBottomSheet {
            return AntiDetectionBottomSheet()
        }
    }

    private var _binding: LayoutAntiDetectionSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences
    private val ghostCloakEngine by lazy { GhostCloakEngine(settingsPrefs) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutAntiDetectionSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        settingsPrefs = AppSettingsPreferences(ctx)
        sessionPrefs = SessionPreferences(ctx)

        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(ctx)
        val primaryCsl = ColorStateList.valueOf(primaryColor)

        binding.btnCloseSheet.backgroundTintList = primaryCsl
        binding.tvLiveNmeaBox.setTextColor(primaryColor)

        loadInitialValues()
        setupListeners()
        startLiveDiagnostics()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())
    }

    private fun loadInitialValues() {
        binding.switchMasterGhostCloak.isChecked = settingsPrefs.isGhostCloakEnabled
        binding.switchNmeaSynthesizer.isChecked = settingsPrefs.isNmeaSynthesisEnabled
        binding.switchClockDrift.isChecked = settingsPrefs.isClockDriftEmulationEnabled
        binding.switchSensorKinematics.isChecked = settingsPrefs.isSensorKinematicsEnabled

        updateSubSwitchesState(settingsPrefs.isGhostCloakEnabled)
    }

    private fun updateSubSwitchesState(masterEnabled: Boolean) {
        val alpha = if (masterEnabled) 1.0f else 0.4f
        binding.switchNmeaSynthesizer.isEnabled = masterEnabled
        binding.switchNmeaSynthesizer.alpha = alpha
        binding.switchClockDrift.isEnabled = masterEnabled
        binding.switchClockDrift.alpha = alpha
        binding.switchSensorKinematics.isEnabled = masterEnabled
        binding.switchSensorKinematics.alpha = alpha

        if (masterEnabled) {
            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(requireContext())
            val lightTintColor = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintColor(requireContext())
            binding.tvGhostCloakBadge.text = "CLOAKED"
            binding.tvGhostCloakBadge.setTextColor(primaryColor)
            binding.tvGhostCloakBadge.backgroundTintList = ColorStateList.valueOf(lightTintColor)
        } else {
            binding.tvGhostCloakBadge.text = "RAW PASS-THROUGH"
            binding.tvGhostCloakBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.badge_warning_text))
            binding.tvGhostCloakBadge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.badge_warning_bg)
        }
    }

    private fun setupListeners() {
        binding.switchMasterGhostCloak.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isGhostCloakEnabled = isChecked
            updateSubSwitchesState(isChecked)
            refreshDiagnostics()
        }

        binding.switchNmeaSynthesizer.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isNmeaSynthesisEnabled = isChecked
            refreshDiagnostics()
        }

        binding.switchClockDrift.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isClockDriftEmulationEnabled = isChecked
            refreshDiagnostics()
        }

        binding.switchSensorKinematics.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.isSensorKinematicsEnabled = isChecked
            refreshDiagnostics()
        }

        binding.btnRootStealthGrant.setOnClickListener {
            performRootStealthCheck()
        }

        binding.btnCloseSheet.setOnClickListener {
            dismiss()
        }
    }

    private fun performRootStealthCheck() {
        val isRooted = PermissionHelper.isDeviceRooted()
        if (isRooted) {
            val granted = PermissionHelper.tryAutoGrantRootMockPermission(requireContext())
            val disabledScanning = PermissionHelper.disableScanningIfRooted(requireContext())
            Toast.makeText(
                requireContext(),
                "Root Stealth: Mock granted ($granted), hardware Wi-Fi scanning suppressed ($disabledScanning), binaries masked.",
                Toast.LENGTH_LONG
            ).show()
            refreshDiagnostics()
        } else {
            Toast.makeText(
                requireContext(),
                "Device is unrooted. Full userspace stealth, GMS Fused mocking, and NMEA synthesis active.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startLiveDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && _binding != null) {
                refreshDiagnostics()
                delay(2000L)
            }
        }
    }

    private fun refreshDiagnostics() {
        if (_binding == null || !isAdded) return

        val ctx = requireContext()
        val isMaster = settingsPrefs.isGhostCloakEnabled
        val isNmea = settingsPrefs.isNmeaSynthesisEnabled
        val isClock = settingsPrefs.isClockDriftEmulationEnabled
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(ctx)
        val primaryCsl = ColorStateList.valueOf(primaryColor)

        val isRooted = PermissionHelper.isDeviceRooted()

        // Providers & Anti-Rubberband Shield
        binding.tvDiagProvider.text = if (isMaster) {
            "Anti-Rubberband Shield: GMS Fused + GPS + Network Multi-Lock Active"
        } else {
            "Multi-Provider Stealth: Disabled (Standard Test Provider)"
        }
        binding.ivDiagProviderIcon.imageTintList = if (isMaster) primaryCsl else ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)

        // Clock Drift
        val uncertaintyNs = (18.0 + Math.random() * 15.0).toFloat()
        binding.tvDiagClock.text = if (isMaster && isClock) {
            String.format(java.util.Locale.US, "Nanosecond Clock Uncertainty: Synchronized (~%.1f ns)", uncertaintyNs)
        } else {
            "Nanosecond Uncertainty: Static (0.0 ns)"
        }
        binding.ivDiagClockIcon.imageTintList = if (isMaster && isClock) primaryCsl else ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)

        // NMEA Feed
        val lat = sessionPrefs.lastLatitude
        val lon = sessionPrefs.lastLongitude
        val speed = sessionPrefs.lastSpeedKmh / 3.6f

        if (isMaster && isNmea) {
            val nmeaSentences = ghostCloakEngine.generateNmeaStream(lat, lon, 15.0, speed, 45.0f)
            binding.tvDiagNmea.text = "NMEA-0183 Sentence Stream: 18 Tracked Satellites"
            binding.ivDiagNmeaIcon.imageTintList = primaryCsl
            binding.tvLiveNmeaBox.text = nmeaSentences.take(3).joinToString("\n")
        } else {
            binding.tvDiagNmea.text = "NMEA-0183 Synthesizer: Inactive"
            binding.ivDiagNmeaIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
            binding.tvLiveNmeaBox.text = "NMEA Stream Disabled. Enable Hardware Synthesizer above."
        }
        binding.tvLiveNmeaBox.setTextColor(primaryColor)

        // Wi-Fi Hardware Scanning Shield
        val isWifiScanningOn = PermissionHelper.isWifiScanningEnabled(ctx)
        if (isWifiScanningOn) {
            binding.tvDiagWifiScan.text = "Wi-Fi Scanning: Detected On (May cause location jumps) • Tap to turn off"
            binding.ivDiagWifiScanIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
            binding.layoutWifiScanNotice.setOnClickListener {
                PermissionHelper.openLocationScanningSettings(ctx)
            }
        } else {
            binding.tvDiagWifiScan.text = "Wi-Fi Scanning: Disabled (Zero-Rubberbanding Lock Active)"
            binding.ivDiagWifiScanIcon.imageTintList = primaryCsl
            binding.layoutWifiScanNotice.setOnClickListener(null)
        }

        // Root Stealth Button Text
        binding.btnRootStealthGrant.text = if (isRooted) "1-Tap Root Stealth (Root Detected)" else "Universal Cloak Active"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
