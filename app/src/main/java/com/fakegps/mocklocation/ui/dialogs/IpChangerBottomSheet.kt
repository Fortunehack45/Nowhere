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

class IpChangerBottomSheet @JvmOverloads constructor(
    private var currentMockLat: Double? = null,
    private var currentMockLon: Double? = null,
    private var onShieldStateChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "IpChangerBottomSheet"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"

        fun newInstance(lat: Double? = null, lon: Double? = null): IpChangerBottomSheet {
            return IpChangerBottomSheet(lat, lon).apply {
                arguments = Bundle().apply {
                    if (lat != null) putDouble(ARG_LAT, lat)
                    if (lon != null) putDouble(ARG_LON, lon)
                }
            }
        }
    }

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
            context?.let { ctx ->
                Toast.makeText(ctx, "VPN Permission was denied", Toast.LENGTH_SHORT).show()
            }
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
        binding.rvIpNodes.setHasFixedSize(true)
        binding.rvIpNodes.itemAnimator = null
        binding.rvIpNodes.adapter = adapter
    }

    private fun setupListeners() {
        binding.tvNodeCountBadge.text = "${IpManager.GLOBAL_PRIVACY_NODES.size} NODES"

        binding.etIpSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (_binding == null) return
                binding.btnClearIpSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                val countMatched = adapter.filter(query)
                binding.tvNodeCountBadge.text = "$countMatched NODES"
                if (countMatched == 0) {
                    binding.layoutNoIpNodes.visibility = View.VISIBLE
                    binding.rvIpNodes.visibility = View.GONE
                    binding.tvNoNodesMessage.text = "No country or city matching \"$query\""
                } else {
                    binding.layoutNoIpNodes.visibility = View.GONE
                    binding.rvIpNodes.visibility = View.VISIBLE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnClearIpSearch.setOnClickListener {
            binding.etIpSearch.text?.clear()
            binding.etIpSearch.clearFocus()
            val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            _binding?.etIpSearch?.windowToken?.let { token ->
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }

        binding.btnRefreshIp.setOnClickListener {
            refreshIpTelemetry()
        }

        binding.switchAutoMatchGps.isChecked = sessionPrefs.autoMatchIpWithGps
        binding.switchAutoMatchGps.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.autoMatchIpWithGps = isChecked
            val lat = currentMockLat
            val lon = currentMockLon
            if (isChecked && lat != null && lon != null) {
                val bestNode = IpManager.findClosestNodeForCoordinates(lat, lon)
                sessionPrefs.activeIpNodeId = bestNode.id
                adapter.setSelectedNodeId(bestNode.id)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Auto-matched to ${bestNode.country} (${bestNode.city})", Toast.LENGTH_SHORT).show()
                }
                if (NowhereVpnService.isRunning) {
                    requestConnectVpn(bestNode)
                }
            }
        }

        binding.btnToggleShield.setOnClickListener {
            if (NowhereVpnService.isRunning) {
                val ctx = context ?: return@setOnClickListener
                Toast.makeText(ctx, "Privacy Shield is locked to Mock GPS to preserve background operation. Tap any country node to switch servers.", Toast.LENGTH_LONG).show()
            } else {
                val node = IpManager.getNodeById(sessionPrefs.activeIpNodeId)
                requestConnectVpn(node)
            }
        }
    }

    private fun requestConnectVpn(node: IpNode) {
        val ctx = context ?: return
        pendingNodeToConnect = node
        val vpnIntent = VpnService.prepare(ctx)
        if (vpnIntent != null) {
            vpnPrepareLauncher.launch(vpnIntent)
        } else {
            startVpnTunnel(node)
        }
    }

    private fun startVpnTunnel(node: IpNode) {
        val ctx = context ?: return
        NowhereVpnService.start(ctx, node.id)
        Toast.makeText(ctx, "Switched Privacy Node to ${node.country} (${node.city})", Toast.LENGTH_SHORT).show()
        onShieldStateChanged?.invoke()
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

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.vpnState.collectLatest { state ->
                if (_binding != null && isAdded) {
                    renderVpnState(state)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            NowhereVpnService.trafficStats.collectLatest { stats ->
                if (_binding != null && isAdded && NowhereVpnService.isRunning) {
                    binding.tvVpnDownloadData.text = stats.formatDownload()
                    binding.tvVpnDownloadRate.text = stats.formatDownloadRate()
                    binding.tvVpnUploadData.text = stats.formatUpload()
                    binding.tvVpnUploadRate.text = stats.formatUploadRate()
                    binding.tvVpnSessionDuration.text = stats.formatDuration()
                }
            }
        }
    }

    private fun renderVpnState(state: NowhereVpnService.VpnState) {
        val context = context ?: return
        if (_binding == null) return

        when (state) {
            is NowhereVpnService.VpnState.Connected -> {
                binding.cardVpnTraffic.visibility = View.VISIBLE
                binding.layoutShieldStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_active_bg)
                binding.viewShieldDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_active_text)
                binding.tvShieldStatus.text = "SHIELD ACTIVE"
                binding.tvShieldStatus.setTextColor(ContextCompat.getColor(context, R.color.badge_active_text))

                binding.tvCurrentIp.text = state.node.virtualIp
                binding.tvIpDetails.text = "${state.node.flagEmoji} ${state.node.city}, ${state.node.country} • Nowhere Privacy Tunnel"

                binding.btnToggleShield.text = "🔒 Privacy Shield Active • Tap Any Node to Switch"
                binding.btnToggleShield.setIconResource(R.drawable.ic_shield_check)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
                binding.btnToggleShield.strokeWidth = 0
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.white)
            }
            is NowhereVpnService.VpnState.Connecting -> {
                binding.cardVpnTraffic.visibility = View.VISIBLE
                binding.tvShieldStatus.text = "CONNECTING..."
                binding.btnToggleShield.text = "Connecting..."
            }
            else -> {
                binding.cardVpnTraffic.visibility = View.GONE
                binding.layoutShieldStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_standby_bg)
                binding.viewShieldDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_standby_text)
                binding.tvShieldStatus.text = "AUTO-ACTIVATES WITH MOCK GPS"
                binding.tvShieldStatus.setTextColor(ContextCompat.getColor(context, R.color.badge_standby_text))

                binding.btnToggleShield.text = "🔒 Start Privacy Tunnel"
                binding.btnToggleShield.setIconResource(R.drawable.ic_shield_check)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
                binding.btnToggleShield.strokeWidth = 0
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.white)

                refreshIpTelemetry()
            }
        }
    }

    private fun refreshIpTelemetry() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ipInfo = IpManager.fetchPublicIpInfo(ctx)
                if (!NowhereVpnService.isRunning && _binding != null && isAdded) {
                    binding.tvCurrentIp.text = ipInfo.ip
                    binding.tvIpDetails.text = "${ipInfo.city}, ${ipInfo.country} • ${ipInfo.isp}"
                }
            } catch (ignored: Exception) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
