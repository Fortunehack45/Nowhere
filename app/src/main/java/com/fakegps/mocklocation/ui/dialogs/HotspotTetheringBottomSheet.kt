package com.fakegps.mocklocation.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutHotspotTetheringBinding
import com.fakegps.mocklocation.hotspot.HotspotLocationClient
import com.fakegps.mocklocation.hotspot.HotspotLocationServer
import com.fakegps.mocklocation.util.PermissionHelper
import com.fakegps.mocklocation.util.QrCodeGenerator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class HotspotTetheringBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "HotspotTetheringBottomSheet"

        fun newInstance(): HotspotTetheringBottomSheet {
            return HotspotTetheringBottomSheet()
        }
    }

    private var _binding: LayoutHotspotTetheringBinding? = null
    private val binding get() = _binding!!

    private var isHostTabActive = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutHotspotTetheringBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Auto-start server in host mode
        HotspotLocationServer.startServer(requireContext())

        setupTabs()
        setupListeners()
        observeServerState()
        observeClientState()
        startPeriodicIpRefresh()
    }

    private fun performHapticFeedback() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (ignored: Exception) {}
    }

    private fun showCopySuccessFeedback(button: MaterialButton? = null) {
        performHapticFeedback()
        button?.let { btn ->
            btn.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).withEndAction {
                btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }.start()
            val origText = btn.text.toString()
            val origIcon = btn.icon
            btn.text = "Copied!"
            btn.setIconResource(R.drawable.ic_check_circle)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1800L)
                if (isAdded) {
                    btn.text = origText
                    btn.icon = origIcon
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabHostMode.setOnClickListener {
            performHapticFeedback()
            switchToHostMode()
        }

        binding.tabClientMode.setOnClickListener {
            performHapticFeedback()
            switchToClientMode()
        }
    }

    private fun switchToHostMode() {
        isHostTabActive = true
        binding.layoutHostContainer.visibility = View.VISIBLE
        binding.layoutClientContainer.visibility = View.GONE

        binding.tabHostMode.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        binding.tabHostMode.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        binding.tabClientMode.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.transparent))
        binding.tabClientMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        HotspotLocationServer.startServer(requireContext())
    }

    private fun switchToClientMode() {
        isHostTabActive = false
        binding.layoutHostContainer.visibility = View.GONE
        binding.layoutClientContainer.visibility = View.VISIBLE

        binding.tabClientMode.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        binding.tabClientMode.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        binding.tabHostMode.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.transparent))
        binding.tabHostMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        if (binding.etHostPhoneUrl.text.isNullOrBlank()) {
            val hostIp = HotspotLocationServer.getHotspotOrWifiIpAddress(requireContext())
            binding.etHostPhoneUrl.setText("http://$hostIp:8088")
        }
    }

    private fun setupListeners() {
        binding.btnHotspotClose.setOnClickListener {
            performHapticFeedback()
            dismiss()
        }

        binding.btnOpenHotspotSettings.setOnClickListener {
            performHapticFeedback()
            try {
                val intent = Intent()
                intent.setClassName("com.android.settings", "com.android.settings.TetherSettings")
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e2: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        binding.switchHotspotServer.setOnCheckedChangeListener { _, isChecked ->
            performHapticFeedback()
            if (isChecked) {
                HotspotLocationServer.startServer(requireContext())
                Toast.makeText(requireContext(), "Hotspot GPS Broadcast Active", Toast.LENGTH_SHORT).show()
            } else {
                HotspotLocationServer.stopServer()
                Toast.makeText(requireContext(), "Hotspot GPS Broadcast Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        val copyAction = {
            val url = HotspotLocationServer.serverUrl.value
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hotspot GPS URL", url)
            clipboard.setPrimaryClip(clip)
            showCopySuccessFeedback(binding.btnCopyHotspotUrl)
            Toast.makeText(requireContext(), "URL copied to clipboard: $url", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyHotspotUrl.setOnClickListener {
            copyAction()
        }

        binding.tvHotspotUrl.setOnClickListener {
            copyAction()
        }

        binding.ivHotspotQrCode.setOnClickListener {
            copyAction()
        }

        binding.btnOpenWebDashboard.setOnClickListener {
            performHapticFeedback()
            val url = HotspotLocationServer.serverUrl.value
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Client Receiver Sync Toggle
        binding.btnToggleClientSync.setOnClickListener {
            performHapticFeedback()
            if (HotspotLocationClient.isSyncing()) {
                HotspotLocationClient.stopSync()
                binding.btnToggleClientSync.text = "Start Syncing Location"
                binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.primary))
                Toast.makeText(requireContext(), "Stopped GPS Sync from Host", Toast.LENGTH_SHORT).show()
            } else {
                val hostUrl = binding.etHostPhoneUrl.text?.toString()?.trim() ?: "http://192.168.43.1:8088"
                HotspotLocationClient.startSync(requireContext(), hostUrl)
                binding.btnToggleClientSync.text = "Disconnect / Stop Sync"
                binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.surface_card_elevated))
                Toast.makeText(requireContext(), "Connecting to Host Phone ($hostUrl)...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenClientDevOptions.setOnClickListener {
            performHapticFeedback()
            PermissionHelper.openDeveloperSettings(requireActivity())
        }
    }

    private fun startPeriodicIpRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(2500L)
                if (isAdded && context != null && isHostTabActive) {
                    HotspotLocationServer.refreshIpAddress(requireContext())
                }
            }
        }
    }

    private fun observeServerState() {
        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.serverUrl.collectLatest { url ->
                binding.tvHotspotUrl.text = url
                val qrBitmap = QrCodeGenerator.generateQrBitmap(url, width = 500, height = 500, context = context)
                if (qrBitmap != null) {
                    binding.ivHotspotQrCode.setImageBitmap(qrBitmap)
                    binding.ivHotspotQrCode.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.allAvailableUrls.collectLatest { urls ->
                val otherUrls = urls.filter { it != HotspotLocationServer.serverUrl.value }
                if (otherUrls.isNotEmpty()) {
                    binding.tvAltIpHeader.visibility = View.VISIBLE
                    binding.layoutAltIpChips.visibility = View.VISIBLE
                    binding.layoutAltIpChips.removeAllViews()

                    for (altUrl in otherUrls) {
                        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                            text = "🔗 $altUrl"
                            textSize = 11f
                            isAllCaps = false
                            setTextColor(ContextCompat.getColor(context, R.color.primary_bright))
                            setOnClickListener {
                                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Alt GPS URL", altUrl))
                                performHapticFeedback()
                                val oldText = text
                                text = "✅ Copied: $altUrl"
                                postDelayed({ text = oldText }, 1800)
                                Toast.makeText(requireContext(), "Copied: $altUrl", Toast.LENGTH_SHORT).show()
                            }
                        }
                        binding.layoutAltIpChips.addView(btn)
                    }
                } else {
                    binding.tvAltIpHeader.visibility = View.GONE
                    binding.layoutAltIpChips.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.isServerRunning.collectLatest { isRunning ->
                binding.switchHotspotServer.isChecked = isRunning
                if (isRunning) {
                    binding.tvHotspotStatusTitle.text = "GPS Broadcasting Active (BETA)"
                    binding.tvHotspotStatusTitle.setTextColor(requireContext().getColor(R.color.text_primary))
                } else {
                    binding.tvHotspotStatusTitle.text = "GPS Broadcasting Stopped"
                    binding.tvHotspotStatusTitle.setTextColor(requireContext().getColor(R.color.text_muted))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.connectedClientsCount.collectLatest { count ->
                binding.tvHotspotClientCount.text = "$count connected client(s)"
            }
        }
    }

    private fun observeClientState() {
        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationClient.syncState.collectLatest { state ->
                when (state) {
                    is HotspotLocationClient.SyncState.Idle -> {
                        binding.dotClientSyncStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_muted))
                        binding.tvClientSyncStatusTitle.text = "Not Syncing"
                        binding.tvClientSyncCoordinates.text = "Waiting for Host GPS stream..."
                        binding.btnToggleClientSync.text = "Start Syncing Location"
                        binding.btnToggleClientSync.setIconResource(R.drawable.ic_bolt)
                        binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                        binding.btnToggleClientSync.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                        binding.btnToggleClientSync.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white))
                        binding.btnOpenClientDevOptions.visibility = View.GONE
                    }
                    is HotspotLocationClient.SyncState.Connecting -> {
                        binding.dotClientSyncStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary_bright))
                        binding.tvClientSyncStatusTitle.text = "Connecting to Host..."
                        binding.tvClientSyncCoordinates.text = state.url
                        binding.btnToggleClientSync.text = "Cancel Connection"
                        binding.btnToggleClientSync.setIconResource(R.drawable.ic_close)
                        binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.btn_stop_bg))
                        binding.btnToggleClientSync.setTextColor(ContextCompat.getColor(requireContext(), R.color.btn_stop_text))
                        binding.btnToggleClientSync.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.btn_stop_text))
                        binding.btnOpenClientDevOptions.visibility = View.GONE
                    }
                    is HotspotLocationClient.SyncState.Synced -> {
                        binding.dotClientSyncStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.badge_success_text))
                        binding.tvClientSyncStatusTitle.text = "LIVE SYNCED WITH HOST PHONE"
                        binding.tvClientSyncCoordinates.text = String.format(
                            Locale.US,
                            "Lat: %.6f\nLon: %.6f\nAlt: %.1f m • Speed: %.1f km/h",
                            state.latitude, state.longitude, state.altitude, state.speedKmh
                        )
                        binding.btnToggleClientSync.text = "Disconnect / Stop Sync"
                        binding.btnToggleClientSync.setIconResource(R.drawable.ic_stop)
                        binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.btn_stop_bg))
                        binding.btnToggleClientSync.setTextColor(ContextCompat.getColor(requireContext(), R.color.btn_stop_text))
                        binding.btnToggleClientSync.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.btn_stop_text))
                        binding.btnOpenClientDevOptions.visibility = View.GONE
                    }
                    is HotspotLocationClient.SyncState.Error -> {
                        binding.dotClientSyncStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                        binding.tvClientSyncStatusTitle.text = "Sync Status"
                        binding.tvClientSyncCoordinates.text = state.message
                        binding.btnToggleClientSync.text = "Start Syncing Location"
                        binding.btnToggleClientSync.setIconResource(R.drawable.ic_bolt)
                        binding.btnToggleClientSync.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                        binding.btnToggleClientSync.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                        binding.btnToggleClientSync.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white))
                        if (state.needsMockPermission) {
                            binding.btnOpenClientDevOptions.visibility = View.VISIBLE
                        } else {
                            binding.btnOpenClientDevOptions.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
