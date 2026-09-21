package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.LayoutDialogIpChangerBinding
import com.fakegps.mocklocation.vpn.NowhereVpnService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Nowhere Ghost VPN Shield Bottom Sheet.
 * Manages the single high-performance WireGuard anti-detection tunnel.
 */
class IpChangerBottomSheet @JvmOverloads constructor(
    private var currentMockLat: Double? = null,
    private var currentMockLon: Double? = null,
    private var onShieldStateChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "IpChangerBottomSheet"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"
        private const val ARG_INITIAL_TAB = "arg_initial_tab"

        fun newInstance(lat: Double? = null, lon: Double? = null, initialTab: Int = 0): IpChangerBottomSheet {
            return IpChangerBottomSheet(lat, lon).apply {
                arguments = Bundle().apply {
                    if (lat != null) putDouble(ARG_LAT, lat)
                    if (lon != null) putDouble(ARG_LON, lon)
                    putInt(ARG_INITIAL_TAB, initialTab)
                }
            }
        }
    }

    private var _binding: LayoutDialogIpChangerBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var settingsPrefs: AppSettingsPreferences

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            NowhereVpnService.start(ctx, "us_central_gcp")
            Toast.makeText(ctx, "Ghost Shield Activated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "VPN Permission required to activate Ghost Shield", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (currentMockLat == null && arguments?.containsKey(ARG_LAT) == true) {
            currentMockLat = arguments?.getDouble(ARG_LAT)
        }
        if (currentMockLon == null && arguments?.containsKey(ARG_LON) == true) {
            currentMockLon = arguments?.getDouble(ARG_LON)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogIpChangerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        sessionPrefs = SessionPreferences(ctx)
        settingsPrefs = AppSettingsPreferences(ctx)

        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, ctx)

        setupControls()
        observeVpnState()
        observeTrafficStats()
    }

    private fun setupControls() {
        val ctx = requireContext()

        // Auto-Sync Switch
        binding.switchAutoVpnSync.isChecked = settingsPrefs.isAutoVpnSyncEnabled
        binding.switchAutoVpnSync.setOnCheckedChangeListener { buttonView, isChecked ->
            buttonView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            settingsPrefs.isAutoVpnSyncEnabled = isChecked
            sessionPrefs.isIpMaskingEnabled = isChecked
            val statusMsg = if (isChecked) "VPN Auto-Sync Enabled" else "VPN Auto-Sync Disabled"
            Toast.makeText(ctx, statusMsg, Toast.LENGTH_SHORT).show()
            onShieldStateChanged?.invoke()
        }

        // Manual Toggle Button
        binding.btnToggleVpnManual.setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (NowhereVpnService.isRunning) {
                NowhereVpnService.stop(ctx)
                Toast.makeText(ctx, "Ghost Shield Deactivated", Toast.LENGTH_SHORT).show()
            } else {
                val prepareIntent = VpnService.prepare(ctx)
                if (prepareIntent != null) {
                    vpnPrepareLauncher.launch(prepareIntent)
                } else {
                    NowhereVpnService.start(ctx, "us_central_gcp")
                    Toast.makeText(ctx, "Activating Ghost Shield...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDone.setOnClickListener {
            dismiss()
        }

        binding.btnIpChangerClose.setOnClickListener {
            dismiss()
        }
    }

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.vpnState.collectLatest { state ->
                val ctx = context ?: return@collectLatest

                when (state) {
                    is NowhereVpnService.VpnState.Connected -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_text)
                        binding.tvVpnStateTitle.text = getString(R.string.vpn_state_protected)
                        binding.tvVpnBadge.text = getString(R.string.vpn_badge_protected)
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_success_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_bg)
                        binding.tvVpnDescription.text = "Kernel WireGuard tunnel active with BBR congestion control and TCP MSS Clamping. Zero packet inspection leaks."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = getString(R.string.vpn_btn_deactivate)
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.text_primary)
                        binding.layoutVpnTelemetry.visibility = View.VISIBLE
                        binding.tvServerNodeInfo.text = "Nowhere Ghost Shield Network • Connected"
                    }
                    is NowhereVpnService.VpnState.Connecting -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
                        binding.tvVpnStateTitle.text = getString(R.string.vpn_state_connecting)
                        binding.tvVpnBadge.text = getString(R.string.vpn_badge_connecting)
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_bg)
                        binding.tvVpnDescription.text = "Negotiating ChaCha20-Poly1305 WireGuard cryptographic handshake on port 51820..."

                        binding.btnToggleVpnManual.isEnabled = false
                        binding.btnToggleVpnManual.text = getString(R.string.vpn_btn_connecting)
                        binding.tvServerNodeInfo.text = "Nowhere Ghost Shield Network • Connecting"
                    }
                    is NowhereVpnService.VpnState.Error -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_text)
                        binding.tvVpnStateTitle.text = getString(R.string.vpn_state_offline)
                        binding.tvVpnBadge.text = getString(R.string.vpn_badge_offline)
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_error_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_bg)
                        binding.tvVpnDescription.text = "Could not complete handshake. Mobile data and Wi-Fi remain safely preserved."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = getString(R.string.vpn_btn_retry)
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                        binding.tvServerNodeInfo.text = "Nowhere Ghost Shield Network • Offline"
                    }
                    is NowhereVpnService.VpnState.Disconnected -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_muted)
                        binding.tvVpnStateTitle.text = getString(R.string.vpn_state_inactive)
                        binding.tvVpnBadge.text = if (settingsPrefs.isAutoVpnSyncEnabled) "SYNCED" else getString(R.string.vpn_badge_inactive)
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                        binding.tvVpnDescription.text = "Connects automatically when mock GPS starts. Tap Activate below to engage protection anytime."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = getString(R.string.vpn_btn_activate)
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                        binding.tvServerNodeInfo.text = "Nowhere Ghost Shield Network • WireGuard 51820"
                    }
                }
            }
        }
    }

    private fun observeTrafficStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.trafficStats.collectLatest { stats ->
                if (_binding != null && NowhereVpnService.isRunning) {
                    binding.tvVpnDownRate.text = stats.formatDownloadRate()
                    binding.tvVpnUpRate.text = stats.formatUploadRate()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
