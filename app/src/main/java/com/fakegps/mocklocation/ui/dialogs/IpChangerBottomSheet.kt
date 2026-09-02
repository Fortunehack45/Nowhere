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
import com.fakegps.mocklocation.vpn.KillSwitchManager
import com.fakegps.mocklocation.vpn.NowhereApiClient
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
    private lateinit var gameAdapter: GameBoostAdapter
    private var pendingNodeToConnect: IpNode? = null
    private var pendingTunnelConfigToConnect: NowhereApiClient.TunnelResponse? = null
    private var pendingGameCustomName: String? = null
    private var activeTab: Int = 0 // 0: Nodes, 1: Game Boost, 2: Kill Switch

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pendingConfig = pendingTunnelConfigToConnect
            val pendingNode = pendingNodeToConnect
            val gameName = pendingGameCustomName

            pendingTunnelConfigToConnect = null
            pendingNodeToConnect = null
            pendingGameCustomName = null

            if (pendingConfig != null) {
                context?.let { ctx ->
                    NowhereVpnService.startWithTunnelResponse(ctx, pendingConfig, gameName)
                }
            } else if (pendingNode != null) {
                startVpnTunnel(pendingNode)
            }
        } else {
            pendingTunnelConfigToConnect = null
            pendingNodeToConnect = null
            pendingGameCustomName = null
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

        setupTabs()
        setupNodesList()
        setupGameBoostList()
        setupKillSwitchControls()
        setupListeners()
        observeVpnState()
        observeKillSwitchState()
        refreshIpTelemetry()

        if (sessionPrefs.isSessionActive && !NowhereVpnService.isRunning) {
            val node = IpManager.findNodeById(sessionPrefs.activeIpNodeId) ?: IpManager.GLOBAL_PRIVACY_NODES.first()
            requestConnectVpn(node)
        }
    }

    private fun setupTabs() {
        switchTab(0)

        binding.tabBtnNodes.setOnClickListener { switchTab(0) }
        binding.tabBtnGameBoost.setOnClickListener { switchTab(1) }
        binding.tabBtnKillSwitch.setOnClickListener { switchTab(2) }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex
        val ctx = context ?: return

        val activeBg = ContextCompat.getColorStateList(ctx, R.color.surface_elevated)
        val inactiveBg = ContextCompat.getColorStateList(ctx, android.R.color.transparent)

        val activeText = ContextCompat.getColor(ctx, R.color.text_primary)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_muted)

        when (tabIndex) {
            0 -> {
                binding.tabBtnNodes.backgroundTintList = activeBg
                binding.tabBtnNodes.setTextColor(activeText)
                binding.tabBtnGameBoost.backgroundTintList = inactiveBg
                binding.tabBtnGameBoost.setTextColor(inactiveText)
                binding.tabBtnKillSwitch.backgroundTintList = inactiveBg
                binding.tabBtnKillSwitch.setTextColor(inactiveText)

                binding.layoutNodesView.visibility = View.VISIBLE
                binding.layoutGameBoostView.visibility = View.GONE
                binding.layoutKillSwitchView.visibility = View.GONE
            }
            1 -> {
                binding.tabBtnNodes.backgroundTintList = inactiveBg
                binding.tabBtnNodes.setTextColor(inactiveText)
                binding.tabBtnGameBoost.backgroundTintList = activeBg
                binding.tabBtnGameBoost.setTextColor(activeText)
                binding.tabBtnKillSwitch.backgroundTintList = inactiveBg
                binding.tabBtnKillSwitch.setTextColor(inactiveText)

                binding.layoutNodesView.visibility = View.GONE
                binding.layoutGameBoostView.visibility = View.VISIBLE
                binding.layoutKillSwitchView.visibility = View.GONE
            }
            2 -> {
                binding.tabBtnNodes.backgroundTintList = inactiveBg
                binding.tabBtnNodes.setTextColor(inactiveText)
                binding.tabBtnGameBoost.backgroundTintList = inactiveBg
                binding.tabBtnGameBoost.setTextColor(inactiveText)
                binding.tabBtnKillSwitch.backgroundTintList = activeBg
                binding.tabBtnKillSwitch.setTextColor(activeText)

                binding.layoutNodesView.visibility = View.GONE
                binding.layoutGameBoostView.visibility = View.GONE
                binding.layoutKillSwitchView.visibility = View.VISIBLE
            }
        }
    }

    private fun setupNodesList() {
        val activeNodeId = sessionPrefs.activeIpNodeId

        adapter = IpNodeAdapter(IpManager.GLOBAL_PRIVACY_NODES, activeNodeId) { selectedNode ->
            sessionPrefs.activeIpNodeId = selectedNode.id
            requestConnectVpn(selectedNode)
        }

        binding.rvIpNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvIpNodes.setHasFixedSize(true)
        binding.rvIpNodes.itemAnimator = null
        binding.rvIpNodes.adapter = adapter
    }

    private fun setupGameBoostList() {
        val games = listOf(
            GameBoostModel("cod_mobile", "Call of Duty: Mobile / Warzone", "🎯", "US East • Google BBR • DSCP 46 EF", 14),
            GameBoostModel("pubg_mobile", "PUBG Mobile / BGMI", "🪂", "US / EU Servers • Zero Jitter", 15),
            GameBoostModel("free_fire", "Free Fire / Free Fire MAX", "🔥", "Fast-Path UDP Routing", 16),
            GameBoostModel("roblox", "Roblox Multi-Server", "🧱", "Low-Latency Anycast Gateway", 12),
            GameBoostModel("mobile_legends", "Mobile Legends: Bang Bang", "⚔️", "Optimized Socket Queues", 11),
            GameBoostModel("brawl_stars", "Brawl Stars / Clash", "⭐", "Direct Server Peering", 12),
            GameBoostModel("genshin_impact", "Genshin Impact / Honkai", "✨", "High-Throughput UDP Route", 14),
            GameBoostModel("ea_fc_mobile", "EA SPORTS FC Mobile / FIFA", "⚽", "Low-Ping Matchmaking Engine", 13)
        )

        gameAdapter = GameBoostAdapter(games, null) { selectedGame ->
            val ctx = context ?: return@GameBoostAdapter
            Toast.makeText(ctx, "⚡ Optimizing ${selectedGame.name} routing...", Toast.LENGTH_SHORT).show()

            viewLifecycleOwner.lifecycleScope.launch {
                val result = NowhereApiClient.optimizeGame(
                    context = ctx,
                    gameId = selectedGame.id
                )
                if (result.isSuccess) {
                    val tunnelConfig = result.getOrNull()
                    if (tunnelConfig != null) {
                        val customName = "🚀 Game Boost: ${selectedGame.name}"
                        val vpnIntent = VpnService.prepare(ctx)
                        if (vpnIntent != null) {
                            pendingTunnelConfigToConnect = tunnelConfig
                            pendingGameCustomName = customName
                            vpnPrepareLauncher.launch(vpnIntent)
                        } else {
                            NowhereVpnService.startWithTunnelResponse(
                                context = ctx,
                                response = tunnelConfig,
                                customName = customName
                            )
                            Toast.makeText(ctx, "🚀 Game Boost Active: ${selectedGame.name} (${tunnelConfig.countryName}, ${tunnelConfig.estimatedPingMs}ms)!", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(ctx, "⚠️ Game Boost optimization returned empty data", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Game boost server unavailable"
                    Toast.makeText(ctx, "❌ Game Boost Failed: $errMsg", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.rvGameBoostList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGameBoostList.setHasFixedSize(true)
        binding.rvGameBoostList.adapter = gameAdapter
    }

    private fun setupKillSwitchControls() {
        binding.switchKillSwitchMaster.isChecked = sessionPrefs.isKillSwitchEnabled
        binding.switchKillSwitchMaster.setOnCheckedChangeListener { _, isChecked ->
            context?.let { ctx ->
                KillSwitchManager.setEnabled(ctx, isChecked)
                Toast.makeText(ctx, if (isChecked) "🛡️ Kill Switch Armed: Leak Protection ON" else "Kill Switch Disabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnKillSwitchBypass.setOnClickListener {
            context?.let { ctx ->
                val newBypassState = !sessionPrefs.isKillSwitchBypassed
                KillSwitchManager.setBypassed(ctx, newBypassState)
                if (newBypassState) {
                    Toast.makeText(ctx, "⚡ Emergency Bypass Active (Internet allowed)", Toast.LENGTH_SHORT).show()
                    binding.btnKillSwitchBypass.text = "🔒 Re-Arm Kill Switch Shield"
                } else {
                    Toast.makeText(ctx, "🛡️ Kill Switch Re-Armed", Toast.LENGTH_SHORT).show()
                    binding.btnKillSwitchBypass.text = "⚡ Temporary Emergency Bypass (Allow Internet)"
                }
            }
        }
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
                        binding.tvKillSwitchDescription.text = "All outgoing traffic is actively monitored. If mock GPS or VPN turns off, real IP/GPS leak prevention engages instantly."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.badge_success_text))
                    }
                    is KillSwitchManager.KillSwitchStatus.Triggered -> {
                        binding.tvKillSwitchStateTitle.text = "⚡ Kill Switch Engaged"
                        binding.tvKillSwitchBadge.text = "LEAK SHIELDED"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.btn_stop_text))
                        binding.tvKillSwitchDescription.text = "Internet access paused because ${status.reason}. Tap Resume to continue or use Temporary Bypass."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.btn_stop_text))
                    }
                    is KillSwitchManager.KillSwitchStatus.Bypassed -> {
                        binding.tvKillSwitchStateTitle.text = "Kill Switch Bypassed"
                        binding.tvKillSwitchBadge.text = "BYPASS ON"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                        binding.tvKillSwitchDescription.text = "Temporary bypass is active. Internet is flowing freely without leak interruption."
                    }
                    is KillSwitchManager.KillSwitchStatus.Disabled -> {
                        binding.tvKillSwitchStateTitle.text = "Kill Switch Disabled"
                        binding.tvKillSwitchBadge.text = "OFF"
                        binding.tvKillSwitchBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                        binding.tvKillSwitchDescription.text = "Enable the Emergency Kill Switch to ensure your real IP and GPS coordinates are never exposed if protection stops."
                        binding.ivKillSwitchShield.setColorFilter(ContextCompat.getColor(ctx, R.color.text_muted))
                    }
                }
            }
        }
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
            val ctx = context ?: return@setOnClickListener
            if (NowhereVpnService.isRunning) {
                NowhereVpnService.stop(ctx)
                sessionPrefs.isIpMaskingEnabled = false
                KillSwitchManager.evaluate(ctx)
                Toast.makeText(ctx, "Privacy Shield Disconnected", Toast.LENGTH_SHORT).show()
                onShieldStateChanged?.invoke()
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
        KillSwitchManager.evaluate(ctx)
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

                binding.btnToggleShield.text = "Disconnect Privacy Shield"
                binding.btnToggleShield.setIconResource(R.drawable.ic_close)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.btn_stop_bg)
                binding.btnToggleShield.strokeWidth = 0
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.btn_stop_text))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.btn_stop_text)
            }
            is NowhereVpnService.VpnState.Connecting -> {
                binding.cardVpnTraffic.visibility = View.VISIBLE
                binding.tvShieldStatus.text = "CONNECTING..."
                binding.btnToggleShield.text = "Connecting..."
                binding.btnToggleShield.setIconResource(R.drawable.ic_shield_check)
                binding.btnToggleShield.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary)
                binding.btnToggleShield.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.btnToggleShield.iconTint = ContextCompat.getColorStateList(context, R.color.white)
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
