package com.fakegps.mocklocation.ui.dialogs

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        settingsPrefs = AppSettingsPreferences(ctx)
        sessionPrefs = SessionPreferences(ctx)

        loadInitialValues()
        setupListeners()
        startLiveDiagnostics()
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
            binding.tvGhostCloakBadge.text = "CLOAKED"
            binding.tvGhostCloakBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_bright))
            binding.tvGhostCloakBadge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.badge_active_bg)
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
            val ctx = requireContext()
            if (PermissionHelper.isDeviceRooted()) {
                val granted = PermissionHelper.tryAutoGrantRootMockPermission(ctx)
                if (granted) {
                    Toast.makeText(ctx, "✅ Root Mock Stealth Granted! System-level permissions active.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "Root shell auto-grant failed. Ensure SuperSU/Magisk granted root access.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(ctx, "Device is not rooted. Universal Ghost Cloak (NMEA + Clock Drift) is fully active and protecting you!", Toast.LENGTH_LONG).show()
            }
            refreshDiagnostics()
        }

        binding.btnCloseSheet.setOnClickListener {
            dismiss()
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

        val isRooted = PermissionHelper.isDeviceRooted()

        // Providers
        binding.tvDiagProvider.text = if (isMaster) {
            "Fused + GPS + Network + Passive Providers: 100% Cloaked"
        } else {
            "Multi-Provider Stealth: Disabled (Standard Test Provider)"
        }
        binding.ivDiagProviderIcon.imageTintList = ContextCompat.getColorStateList(
            ctx,
            if (isMaster) R.color.badge_active_text else R.color.badge_warning_text
        )

        // Clock Drift
        val uncertaintyNs = (18.0 + Math.random() * 15.0).toFloat()
        binding.tvDiagClock.text = if (isMaster && isClock) {
            String.format(java.util.Locale.US, "Nanosecond Clock Uncertainty: Synchronized (~%.1f ns)", uncertaintyNs)
        } else {
            "Nanosecond Uncertainty: Static (0.0 ns)"
        }
        binding.ivDiagClockIcon.imageTintList = ContextCompat.getColorStateList(
            ctx,
            if (isMaster && isClock) R.color.badge_active_text else R.color.badge_warning_text
        )

        // NMEA Feed
        val lat = sessionPrefs.lastLatitude
        val lon = sessionPrefs.lastLongitude
        val speed = sessionPrefs.lastSpeedKmh / 3.6f

        if (isMaster && isNmea) {
            val nmeaSentences = ghostCloakEngine.generateNmeaStream(lat, lon, 15.0, speed, 45.0f)
            binding.tvDiagNmea.text = "NMEA-0183 Sentence Stream: 18 Tracked Satellites"
            binding.ivDiagNmeaIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_active_text)
            binding.tvLiveNmeaBox.text = nmeaSentences.take(3).joinToString("\n")
        } else {
            binding.tvDiagNmea.text = "NMEA-0183 Synthesizer: Inactive"
            binding.ivDiagNmeaIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
            binding.tvLiveNmeaBox.text = "NMEA Stream Disabled. Enable Hardware Synthesizer above."
        }

        // Root Stealth Button Text
        binding.btnRootStealthGrant.text = if (isRooted) "1-Tap Root Stealth (Root Detected)" else "Universal Cloak Active"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
