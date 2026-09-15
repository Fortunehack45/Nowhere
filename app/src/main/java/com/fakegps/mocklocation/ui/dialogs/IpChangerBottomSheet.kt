package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.LayoutDialogIpChangerBinding
import com.fakegps.mocklocation.vpn.KillSwitchManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Emergency Privacy Kill Switch Bottom Sheet.
 * Manages OS-level local packet sinkhole leak protection.
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

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            KillSwitchManager.setEnabled(ctx, true)
            binding.switchKillSwitchMaster.isChecked = true
            Toast.makeText(ctx, "Kill Switch Armed: Leak Protection ON", Toast.LENGTH_SHORT).show()
        } else {
            binding.switchKillSwitchMaster.isChecked = false
            KillSwitchManager.setEnabled(ctx, false)
            Toast.makeText(ctx, "VPN Permission is required for OS Kill Switch sinkhole", Toast.LENGTH_LONG).show()
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
        sessionPrefs = SessionPreferences(requireContext())

        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())

        setupKillSwitchControls()
        observeKillSwitchState()
        KillSwitchManager.evaluate(requireContext())
    }

    private fun setupKillSwitchControls() {
        binding.switchKillSwitchMaster.isChecked = sessionPrefs.isKillSwitchEnabled

        binding.switchKillSwitchMaster.setOnCheckedChangeListener { _, isChecked ->
            val ctx = context ?: return@setOnCheckedChangeListener
            if (isChecked) {
                val prepareIntent = VpnService.prepare(ctx)
                if (prepareIntent != null) {
                    vpnPrepareLauncher.launch(prepareIntent)
                } else {
                    KillSwitchManager.setEnabled(ctx, true)
                    Toast.makeText(ctx, "Kill Switch Armed: Leak Protection ON", Toast.LENGTH_SHORT).show()
                }
            } else {
                KillSwitchManager.setEnabled(ctx, false)
                Toast.makeText(ctx, "Kill Switch Disabled", Toast.LENGTH_SHORT).show()
            }
            onShieldStateChanged?.invoke()
        }

        binding.btnKillSwitchBypass.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val newBypassState = !sessionPrefs.isKillSwitchBypassed
            KillSwitchManager.setBypassed(ctx, newBypassState)
            if (newBypassState) {
                Toast.makeText(ctx, "Emergency Bypass Active (Internet allowed)", Toast.LENGTH_SHORT).show()
                binding.btnKillSwitchBypass.text = "Re-Arm Kill Switch Shield"
            } else {
                Toast.makeText(ctx, "Kill Switch Re-Armed", Toast.LENGTH_SHORT).show()
                binding.btnKillSwitchBypass.text = "Temporary Emergency Bypass (Allow Internet)"
            }
            onShieldStateChanged?.invoke()
        }

        binding.btnResumeMockLocation.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            dismiss()
            Toast.makeText(ctx, "Starting mock GPS to unblock internet...", Toast.LENGTH_SHORT).show()
            val intent = Intent(ctx, com.fakegps.mocklocation.service.MockLocationService::class.java).apply {
                action = com.fakegps.mocklocation.service.MockLocationService.ACTION_START_FIXED
                putExtra(com.fakegps.mocklocation.service.MockLocationService.EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                putExtra(com.fakegps.mocklocation.service.MockLocationService.EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        binding.btnIpChangerClose.setOnClickListener { dismiss() }
        binding.btnDone.setOnClickListener { dismiss() }
    }

    private fun observeKillSwitchState() {
        viewLifecycleOwner.lifecycleScope.launch {
            KillSwitchManager.status.collectLatest { status ->
                if (_binding == null || !isAdded) return@collectLatest
                val ctx = context ?: return@collectLatest

                when (status) {
                    is KillSwitchManager.KillSwitchStatus.Armed -> {
                        binding.tvKillSwitchStateTitle.text = "Kill Switch Armed & Active"
                        binding.tvKillSwitchBadge.text = "ARMED"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_success_text))
                        binding.tvKillSwitchDescription.text = "Mock GPS is actively injecting. All outgoing internet traffic is shielded. If mock GPS stops, internet will halt instantly to prevent real location leaks."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.badge_success_text))
                        binding.btnResumeMockLocation.visibility = View.GONE
                        binding.btnKillSwitchBypass.visibility = View.VISIBLE
                        binding.btnKillSwitchBypass.text = "Temporary Emergency Bypass"
                    }
                    is KillSwitchManager.KillSwitchStatus.Triggered -> {
                        binding.tvKillSwitchStateTitle.text = "Internet Traffic Halted"
                        binding.tvKillSwitchBadge.text = "LEAK SHIELDED"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.btn_stop_text))
                        binding.tvKillSwitchDescription.text = "All outgoing internet traffic is stopped at the OS level because ${status.reason}. Resume mock GPS or use emergency bypass."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.btn_stop_text))
                        binding.btnResumeMockLocation.visibility = View.VISIBLE
                        binding.btnKillSwitchBypass.visibility = View.VISIBLE
                        binding.btnKillSwitchBypass.text = "Temporary Emergency Bypass (Allow Internet)"
                    }
                    is KillSwitchManager.KillSwitchStatus.Bypassed -> {
                        binding.tvKillSwitchStateTitle.text = "Kill Switch Bypassed"
                        binding.tvKillSwitchBadge.text = "BYPASS ON"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                        binding.tvKillSwitchDescription.text = "Temporary emergency bypass is active. Internet traffic is flowing freely without leak interruption."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                        binding.btnResumeMockLocation.visibility = View.GONE
                        binding.btnKillSwitchBypass.visibility = View.VISIBLE
                        binding.btnKillSwitchBypass.text = "Re-Arm Kill Switch Shield"
                    }
                    is KillSwitchManager.KillSwitchStatus.Disabled -> {
                        binding.tvKillSwitchStateTitle.text = "Kill Switch Disabled"
                        binding.tvKillSwitchBadge.text = "OFF"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                        binding.tvKillSwitchDescription.text = "Enable the Emergency Kill Switch to ensure your real IP and GPS coordinates are never exposed if mock simulation stops."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.text_muted))
                        binding.btnResumeMockLocation.visibility = View.GONE
                        binding.btnKillSwitchBypass.visibility = View.GONE
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
