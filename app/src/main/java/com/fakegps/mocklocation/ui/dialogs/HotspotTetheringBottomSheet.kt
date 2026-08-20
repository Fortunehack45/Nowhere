package com.fakegps.mocklocation.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.LayoutHotspotTetheringBinding
import com.fakegps.mocklocation.hotspot.HotspotLocationServer
import com.fakegps.mocklocation.util.QrCodeGenerator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HotspotTetheringBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "HotspotTetheringBottomSheet"

        fun newInstance(): HotspotTetheringBottomSheet {
            return HotspotTetheringBottomSheet()
        }
    }

    private var _binding: LayoutHotspotTetheringBinding? = null
    private val binding get() = _binding!!

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

        // Ensure server is started
        HotspotLocationServer.startServer(requireContext())

        setupListeners()
        observeServerState()
        startPeriodicIpRefresh()
    }

    private fun setupListeners() {
        binding.btnHotspotClose.setOnClickListener {
            dismiss()
        }

        binding.btnOpenHotspotSettings.setOnClickListener {
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
            if (isChecked) {
                HotspotLocationServer.startServer(requireContext())
                Toast.makeText(requireContext(), "🛰️ Hotspot GPS Broadcast Started", Toast.LENGTH_SHORT).show()
            } else {
                HotspotLocationServer.stopServer()
                Toast.makeText(requireContext(), "Hotspot GPS Broadcast Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyHotspotUrl.setOnClickListener {
            val url = HotspotLocationServer.serverUrl.value
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hotspot GPS URL", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "✅ URL copied to clipboard: $url", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenWebDashboard.setOnClickListener {
            val url = HotspotLocationServer.serverUrl.value
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPeriodicIpRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(2000L)
                if (isAdded && context != null) {
                    HotspotLocationServer.refreshIpAddress(requireContext())
                }
            }
        }
    }

    private fun observeServerState() {
        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.serverUrl.collectLatest { url ->
                binding.tvHotspotUrl.text = url
                val qrBitmap = QrCodeGenerator.generateQrBitmap(url, width = 400, height = 400)
                if (qrBitmap != null) {
                    binding.ivHotspotQrCode.setImageBitmap(qrBitmap)
                    binding.ivHotspotQrCode.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.isServerRunning.collectLatest { isRunning ->
                binding.switchHotspotServer.isChecked = isRunning
                if (isRunning) {
                    binding.tvHotspotStatusTitle.text = "GPS Broadcasting Active"
                    binding.tvHotspotStatusTitle.setTextColor(requireContext().getColor(R.color.text_primary))
                } else {
                    binding.tvHotspotStatusTitle.text = "GPS Broadcasting Stopped"
                    binding.tvHotspotStatusTitle.setTextColor(requireContext().getColor(R.color.text_muted))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            HotspotLocationServer.connectedClientsCount.collectLatest { count ->
                binding.tvHotspotClientCount.text = "$count connected client(s) streaming live GPS"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
