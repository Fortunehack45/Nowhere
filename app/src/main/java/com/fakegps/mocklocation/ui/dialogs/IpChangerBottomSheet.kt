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
        rebindTexts()
        observeVpnState()
        observeTrafficStats()

        viewLifecycleOwner.lifecycleScope.launch {
            com.fakegps.mocklocation.util.LocaleHelper.languageChangeFlow.collectLatest {
                rebindTexts()
                context?.let { currentCtx ->
                    renderVpnState(NowhereVpnService.vpnState.value, currentCtx)
                }
            }
        }
    }

    private fun rebindTexts() {
        if (_binding == null) return
        val ctx = context ?: return
        binding.tvSheetTitle.text = ctx.getString(R.string.ip_changer_nowhere_ghost_vpn)
        binding.tvSheetSubtitle.text = ctx.getString(R.string.ip_changer_enterprise_antidetection_wireguard_shield)
        binding.tvLabelDownload.text = ctx.getString(R.string.ip_changer_download)
        binding.tvLabelUpload.text = ctx.getString(R.string.ip_changer_upload)
        binding.tvLabelLatency.text = ctx.getString(R.string.ip_changer_latency)
        binding.tvSyncTitle.text = ctx.getString(R.string.ip_changer_sync_with_mock_location)
        binding.tvSyncDesc.text = ctx.getString(R.string.ip_changer_engages_vpn_on_mock_start)
        binding.tvSpecsHeader.text = ctx.getString(R.string.ip_changer_antidetection_specifications)
        binding.tvSpecWireguard.text = ctx.getString(R.string.ip_changer_kernel_wireguard_udp_51820_chacha20poly1305)
        binding.tvSpecBbr.text = ctx.getString(R.string.ip_changer_google_bbr_congestion_control_zerobufferbloat)
        binding.tvSpecTcpMss.text = ctx.getString(R.string.ip_changer_tcp_mss_clamping_pmtu_modifies)
        binding.tvSpecEncryptedDns.text = ctx.getString(R.string.ip_changer_encrypted_dns_shield_routes_100)
        binding.btnDone.text = ctx.getString(R.string.btn_done)
    }

    private fun setupControls() {
        val ctx = requireContext()

        // Auto-Sync Switch
        binding.switchAutoVpnSync.isChecked = settingsPrefs.isAutoVpnSyncEnabled
        binding.switchAutoVpnSync.setOnCheckedChangeListener { buttonView, isChecked ->
            buttonView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            settingsPrefs.isAutoVpnSyncEnabled = isChecked
            sessionPrefs.isIpMaskingEnabled = isChecked
            if (!isChecked && NowhereVpnService.isRunning) {
                NowhereVpnService.stop(ctx)
            } else if (isChecked && !NowhereVpnService.isRunning && com.fakegps.mocklocation.service.MockLocationService.isSimulationRunning()) {
                val liveState = com.fakegps.mocklocation.service.MockLocationService.activeInstance?.serviceState?.value
                val lat = if (liveState is com.fakegps.mocklocation.service.ServiceState.Running) liveState.latitude else sessionPrefs.lastLatitude
                val lon = if (liveState is com.fakegps.mocklocation.service.ServiceState.Running) liveState.longitude else sessionPrefs.lastLongitude
                val node = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(lat, lon)
                val prepareIntent = VpnService.prepare(ctx)
                if (prepareIntent != null) {
                    vpnPrepareLauncher.launch(prepareIntent)
                } else {
                    NowhereVpnService.start(ctx, node.id)
                }
            }
            val statusMsg = if (isChecked) "Sync with Mock Location Enabled" else "Direct Carrier Internet Active ⚡"
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

    private fun renderVpnState(state: NowhereVpnService.VpnState, ctx: Context) {
        if (_binding == null) return
        when (state) {
            is NowhereVpnService.VpnState.Connected -> {
                binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_text)
                binding.tvVpnStateTitle.text = ctx.getString(R.string.vpn_state_protected)
                binding.tvVpnBadge.text = ctx.getString(R.string.vpn_badge_protected)
                binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_success_text))
                binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_bg)
                binding.tvVpnDescription.text = ctx.getString(R.string.vpn_desc_connected)

                binding.btnToggleVpnManual.isEnabled = true
                binding.btnToggleVpnManual.text = ctx.getString(R.string.vpn_btn_deactivate)
                binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.text_primary)
                binding.layoutVpnTelemetry.visibility = View.VISIBLE
                binding.tvServerNodeInfo.text = ctx.getString(R.string.vpn_node_connected)
            }
            is NowhereVpnService.VpnState.Connecting -> {
                binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
                binding.tvVpnStateTitle.text = ctx.getString(R.string.vpn_state_connecting)
                binding.tvVpnBadge.text = ctx.getString(R.string.vpn_badge_connecting)
                binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_bg)
                binding.tvVpnDescription.text = ctx.getString(R.string.vpn_desc_connecting)

                binding.btnToggleVpnManual.isEnabled = false
                binding.btnToggleVpnManual.text = ctx.getString(R.string.vpn_btn_connecting)
                binding.tvServerNodeInfo.text = ctx.getString(R.string.vpn_node_connecting)
            }
            is NowhereVpnService.VpnState.Error -> {
                binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_text)
                binding.tvVpnStateTitle.text = ctx.getString(R.string.vpn_state_offline)
                binding.tvVpnBadge.text = ctx.getString(R.string.vpn_badge_offline)
                binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_error_text))
                binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_bg)
                binding.tvVpnDescription.text = ctx.getString(R.string.vpn_desc_error)

                binding.btnToggleVpnManual.isEnabled = true
                binding.btnToggleVpnManual.text = ctx.getString(R.string.vpn_btn_retry)
                binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                binding.tvServerNodeInfo.text = ctx.getString(R.string.vpn_node_offline)
            }
            is NowhereVpnService.VpnState.Disconnected -> {
                binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_muted)
                binding.tvVpnStateTitle.text = ctx.getString(R.string.vpn_state_inactive)
                binding.tvVpnBadge.text = if (settingsPrefs.isAutoVpnSyncEnabled) ctx.getString(R.string.vpn_badge_synced) else ctx.getString(R.string.vpn_badge_inactive)
                binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                binding.tvVpnDescription.text = ctx.getString(R.string.vpn_desc_disconnected)

                binding.btnToggleVpnManual.isEnabled = true
                binding.btnToggleVpnManual.text = ctx.getString(R.string.vpn_btn_activate)
                binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                binding.tvServerNodeInfo.text = ctx.getString(R.string.vpn_node_default)
            }
        }
    }

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.vpnState.collectLatest { state ->
                val ctx = context ?: return@collectLatest
                renderVpnState(state, ctx)
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
