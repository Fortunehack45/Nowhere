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

        // Server Configuration Dialog
        updateServerInfoDisplay()
        binding.layoutServerConfig.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showServerConfigDialog()
        }

        binding.btnDone.setOnClickListener {
            dismiss()
        }

        binding.btnIpChangerClose.setOnClickListener {
            dismiss()
        }
    }

    private fun updateServerInfoDisplay() {
        val ctx = context ?: return
        val currentUrl = com.fakegps.mocklocation.vpn.NowhereApiClient.getCustomBackendUrl(ctx)
        val host = currentUrl.substringAfter("://").substringBefore(":").substringBefore("/")
        binding.tvServerNodeInfo.text = "Server: $host • Port 51820"
    }

    private fun showServerConfigDialog() {
        val ctx = context ?: return
        val currentUrl = com.fakegps.mocklocation.vpn.NowhereApiClient.getCustomBackendUrl(ctx)
        val currentHost = currentUrl.substringAfter("://").substringBefore(":").substringBefore("/")
        val input = android.widget.EditText(ctx).apply {
            setText(currentHost)
            setSelection(text.length)
            hint = "e.g. 34.123.45.67"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Configure VPN Server IP")
            .setMessage("Enter your live Google Cloud VM External IP or Hostname:")
            .setView(container)
            .setPositiveButton("Save & Connect") { _, _ ->
                var rawInput = input.text.toString().trim()
                if (rawInput.isNotBlank()) {
                    val cleanHost = rawInput.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
                    val newUrl = "http://$cleanHost:8080"
                    com.fakegps.mocklocation.vpn.NowhereApiClient.setCustomBackendUrl(ctx, newUrl)
                    updateServerInfoDisplay()
                    Toast.makeText(ctx, "Server IP updated to $cleanHost!", Toast.LENGTH_SHORT).show()
                    val prepareIntent = VpnService.prepare(ctx)
                    if (prepareIntent != null) {
                        vpnPrepareLauncher.launch(prepareIntent)
                    } else {
                        NowhereVpnService.start(ctx, "us_central_gcp")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.vpnState.collectLatest { state ->
                val ctx = context ?: return@collectLatest
                val currentUrl = com.fakegps.mocklocation.vpn.NowhereApiClient.getCustomBackendUrl(ctx)
                val host = currentUrl.substringAfter("://").substringBefore(":").substringBefore("/")

                when (state) {
                    is NowhereVpnService.VpnState.Connected -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_text)
                        binding.tvVpnStateTitle.text = "Ghost Shield Protected"
                        binding.tvVpnBadge.text = "PROTECTED"
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_success_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_success_bg)
                        binding.tvVpnDescription.text = "Kernel WireGuard tunnel active with BBR congestion control and TCP MSS Clamping. Zero packet inspection leaks."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = "Deactivate Ghost Shield"
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.text_primary)
                        binding.layoutVpnTelemetry.visibility = View.VISIBLE
                        binding.tvServerNodeInfo.text = "Server: $host • Live & Protected"
                    }
                    is NowhereVpnService.VpnState.Connecting -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_text)
                        binding.tvVpnStateTitle.text = "Connecting to Secure Tunnel..."
                        binding.tvVpnBadge.text = "CONNECTING"
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_warning_bg)
                        binding.tvVpnDescription.text = "Negotiating ChaCha20-Poly1305 WireGuard handshake with $host:51820..."

                        binding.btnToggleVpnManual.isEnabled = false
                        binding.btnToggleVpnManual.text = "Connecting..."
                    }
                    is NowhereVpnService.VpnState.Error -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_text)
                        binding.tvVpnStateTitle.text = "VPN Server Offline"
                        binding.tvVpnBadge.text = "OFFLINE"
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_error_text))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.badge_error_bg)
                        binding.tvVpnDescription.text = "Cannot reach server at $host. Ensure your Google Cloud VM is running or tap 'Configure Server IP' below."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = "Retry Ghost Shield Connection"
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                        binding.tvServerNodeInfo.text = "Server: $host • Offline (Tap to change)"
                    }
                    is NowhereVpnService.VpnState.Disconnected -> {
                        binding.ivVpnShield.setImageResource(R.drawable.ic_shield_check)
                        binding.ivVpnShield.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_muted)
                        binding.tvVpnStateTitle.text = "Ghost Shield Inactive"
                        binding.tvVpnBadge.text = if (settingsPrefs.isAutoVpnSyncEnabled) "SYNCED" else "IDLE"
                        binding.tvVpnBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                        binding.tvVpnBadge.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
                        binding.tvVpnDescription.text = "Connects automatically when mock GPS starts. Tap Activate below to engage protection anytime."

                        binding.btnToggleVpnManual.isEnabled = true
                        binding.btnToggleVpnManual.text = "Activate Ghost Shield Now"
                        binding.btnToggleVpnManual.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.primary)
                        binding.btnToggleVpnManual.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                        binding.btnToggleVpnManual.iconTint = ContextCompat.getColorStateList(ctx, R.color.white)
                        binding.tvServerNodeInfo.text = "Server: $host • Port 51820"
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
