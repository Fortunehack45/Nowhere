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
import androidx.recyclerview.widget.LinearLayoutManager
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.LayoutDialogIpChangerBinding
import com.fakegps.mocklocation.vpn.IpManager
import com.fakegps.mocklocation.vpn.IpNode
import com.fakegps.mocklocation.vpn.NowhereVpnService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IpChangerBottomSheet(
    private val currentMockLat: Double? = null,
    private val currentMockLon: Double? = null,
    private val onShieldStateChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogIpChangerBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var adapter: IpNodeAdapter
    private var pendingNodeToConnect: IpNode? = null

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingNodeToConnect?.let { node ->
                startVpnTunnel(node)
            }
        } else {
            Toast.makeText(requireContext(), "VPN Permission was denied", Toast.LENGTH_SHORT).show()
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

        setupNodesList()
        setupListeners()
        observeVpnState()
        refreshIpTelemetry()
    }

    private fun setupNodesList() {
        val activeNodeId = sessionPrefs.activeIpNodeId

        adapter = IpNodeAdapter(IpManager.GLOBAL_PRIVACY_NODES, activeNodeId) { selectedNode ->
            sessionPrefs.activeIpNodeId = selectedNode.id
            if (NowhereVpnService.isRunning) {
                // Seamlessly switch to new node
                requestConnectVpn(selectedNode)
            }
        }

        binding.rvIpNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvIpNodes.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnRefreshIp.setOnClickListener {
            refreshIpTelemetry()
        }

        binding.switchAutoMatchGps.isChecked = sessionPrefs.autoMatchIpWithGps
        binding.switchAutoMatchGps.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.autoMatchIpWithGps = isChecked
            if (isChecked && currentMockLat != null && currentMockLon != null) {
                val bestNode = IpManager.findClosestNodeForCoordinates(currentMockLat, currentMockLon)
                sessionPrefs.activeIpNodeId = bestNode.id
                adapter.setSelectedNodeId(bestNode.id)
                Toast.makeText(requireContext(), "Auto-matched to ${bestNode.country} (${bestNode.city})", Toast.LENGTH_SHORT).show()
                if (NowhereVpnService.isRunning) {
                    requestConnectVpn(bestNode)
                }
            }
        }

        binding.btnToggleShield.setOnClickListener {
            if (NowhereVpnService.isRunning) {
                NowhereVpnService.stop(requireContext())
                Toast.makeText(requireContext(), "IP Shield Disconnected", Toast.LENGTH_SHORT).show()
                onShieldStateChanged?.invoke()
            } else {
                val node = IpManager.getNodeById(sessionPrefs.activeIpNodeId)
                requestConnectVpn(node)
            }
        }
    }

    private fun requestConnectVpn(node: IpNode) {
        pendingNodeToConnect = node
        val vpnIntent = VpnService.prepare(requireContext())
        if (vpnIntent != null) {
            vpnPrepareLauncher.launch(vpnIntent)
        } else {
            startVpnTunnel(node)
        }
    }

    private fun startVpnTunnel(node: IpNode) {
        NowhereVpnService.start(requireContext(), node.id)
        Toast.makeText(requireContext(), "Connecting to ${node.name}...", Toast.LENGTH_SHORT).show()
        onShieldStateChanged?.invoke()
    }

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.vpnState.collectLatest { state ->
                renderVpnState(state)
            }
        }
    }

    private fun renderVpnState(state: NowhereVpnService.VpnState) {
        val context = context ?: return
        when (state) {
            is NowhereVpnService.VpnState.Connected -> {
                binding.layoutShieldStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_active_bg)
                binding.viewShieldDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_active_text)
                binding.tvShieldStatus.text = "SHIELD ACTIVE"
                binding.tvShieldStatus.setTextColor(ContextCompat.getColor(context, R.color.badge_active_text))

                binding.tvCurrentIp.text = state.node.virtualIp
                binding.tvIpDetails.text = "${state.node.flagEmoji} ${state.node.city}, ${state.node.country} • Nowhere Privacy Tunnel"

                binding.btnToggleShield.text = "Disconnect IP Shield"
                binding.btnToggleShield.setIconResource(R.drawable.ic_clear)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.card_dark)
                binding.btnToggleShield.strokeColor = ContextCompat.getColorStateList(context, R.color.primary)
                binding.btnToggleShield.strokeWidth = 2
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.primary))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.primary)
            }
            is NowhereVpnService.VpnState.Connecting -> {
                binding.tvShieldStatus.text = "CONNECTING..."
                binding.btnToggleShield.text = "Connecting..."
            }
            else -> {
                binding.layoutShieldStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_standby_bg)
                binding.viewShieldDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_standby_text)
                binding.tvShieldStatus.text = "DIRECT IP"
                binding.tvShieldStatus.setTextColor(ContextCompat.getColor(context, R.color.badge_standby_text))

                binding.btnToggleShield.text = "Activate IP Shield"
                binding.btnToggleShield.setIconResource(R.drawable.ic_check)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
                binding.btnToggleShield.strokeWidth = 0
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.white)

                refreshIpTelemetry()
            }
        }
    }

    private fun refreshIpTelemetry() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ipInfo = IpManager.fetchPublicIpInfo(requireContext())
            if (!NowhereVpnService.isRunning && _binding != null) {
                binding.tvCurrentIp.text = ipInfo.ip
                binding.tvIpDetails.text = "${ipInfo.city}, ${ipInfo.country} • ${ipInfo.isp}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
