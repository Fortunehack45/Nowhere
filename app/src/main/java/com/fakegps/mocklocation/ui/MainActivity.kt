package com.fakegps.mocklocation.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.ui.tour.SpotlightStep
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.db.SearchHistoryItem
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivityMainBinding
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.service.MockLocationServiceReceiver
import com.fakegps.mocklocation.service.ServiceState
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.SimulationMode
import com.fakegps.mocklocation.ui.custom.JoystickView
import com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog
import com.fakegps.mocklocation.ui.dialogs.HistoryBottomSheet
import com.fakegps.mocklocation.ui.dialogs.IpChangerBottomSheet
import com.fakegps.mocklocation.ui.dialogs.SaveFavoriteDialog
import com.fakegps.mocklocation.ui.dialogs.SaveRouteDialog
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.ui.dialogs.WeatherBottomSheet
import com.fakegps.mocklocation.ui.dialogs.WidgetGalleryBottomSheet
import com.fakegps.mocklocation.ui.favorites.FavoritesBottomSheet
import com.fakegps.mocklocation.ui.routes.SavedRoutesBottomSheet
import com.fakegps.mocklocation.util.PermissionHelper
import com.fakegps.mocklocation.weather.WeatherManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsPrefs: AppSettingsPreferences

    private var mockService: MockLocationService? = null
    private var isServiceBound = false

    private lateinit var unifiedSearchAdapter: UnifiedSearchAdapter
    private var fixedPinMarker: Marker? = null
    private var liveSimMarker: Marker? = null
    private val routeMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null

    private var latestHistoryItems: List<SearchHistoryItem> = emptyList()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MockLocationService.LocalBinder
            mockService = binder?.getService()
            isServiceBound = true

            mockService?.let { svc ->
                lifecycleScope.launch {
                    svc.serviceState.collectLatest { state ->
                        viewModel.onServiceStateUpdated(state)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
            isServiceBound = false
        }
    }

    private val gpxPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    viewModel.importGpxRoute(stream)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read GPX file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!fineGranted) {
            Toast.makeText(this, "Location permission is required for accurate simulation.", Toast.LENGTH_LONG).show()
        }
        viewModel.refreshPermissionStates()
    }

    private var pendingSimulationAction: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshPermissionStates()
        if (isGranted) {
            Toast.makeText(this, "🔔 Notifications enabled", Toast.LENGTH_SHORT).show()
        }
        val action = pendingSimulationAction
        pendingSimulationAction = null
        action?.invoke()
    }

    fun checkNotificationPermissionBeforeSimulation(onProceed: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionHelper.hasNotificationPermission(this)) {
            pendingSimulationAction = onProceed
            com.fakegps.mocklocation.ui.dialogs.NotificationPermissionDialog(
                activity = this,
                onRequestPermission = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = {
                    val action = pendingSimulationAction
                    pendingSimulationAction = null
                    action?.invoke()
                }
            ).show()
        } else {
            onProceed()
        }
    }

    private var pendingVpnNodeId: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val nodeId = pendingVpnNodeId ?: SessionPreferences(this).activeIpNodeId
            com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, nodeId)
            Toast.makeText(this, "VPN Privacy Shield Activated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN permission is needed to activate Privacy Shield", Toast.LENGTH_SHORT).show()
        }
        pendingVpnNodeId = null
    }

    fun startVpnWithPermissionCheck(nodeId: String) {
        val prepareIntent = android.net.VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnNodeId = nodeId
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, nodeId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupTouchIsolation()
        setupMap()
        setupSearch()
        setupModeTabs()
        setupControls()
        setupRouteActions()
        setupJoystick()
        setupBottomDeckToggle()
        setupFloatingButtons()
        setupIpShield()
        setupGhostCloak()
        requestInitialPermissions()
        observeUiState()
        com.fakegps.mocklocation.util.AppReviewManager.incrementLaunchCount(this)

        // Handle edge-to-edge system bar insets (Android 15+ & targetSdk 35)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())

            val baseTopPadding = (16 * resources.displayMetrics.density).toInt()
            binding.layoutTopHeader.setPadding(
                binding.layoutTopHeader.paddingLeft,
                baseTopPadding + statusBarInset.top,
                binding.layoutTopHeader.paddingRight,
                binding.layoutTopHeader.paddingBottom
            )

            val baseBottomPadding = (16 * resources.displayMetrics.density).toInt()
            binding.layoutBottomContainer.setPadding(
                binding.layoutBottomContainer.paddingLeft,
                binding.layoutBottomContainer.paddingTop,
                binding.layoutBottomContainer.paddingRight,
                baseBottomPadding + navBarInset.bottom
            )

            insets
        }

        binding.btnHeaderWidgets.setOnClickListener {
            WidgetGalleryBottomSheet().show(supportFragmentManager, "WIDGET_GALLERY")
        }

        binding.btnHeaderSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.layoutPremiumBadge.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.TAG)
        }

        binding.layoutWeatherBadge.setOnClickListener {
            val state = viewModel.uiState.value
            WeatherBottomSheet(state.fixedLatitude, state.fixedLongitude).show(supportFragmentManager, "WEATHER_DIALOG")
        }

        binding.layoutSessionTimerBadge.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this) {
                startFixedSpoofing()
            }.show()
        }

        binding.layoutHotspotBadge.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.TAG)
        }

        lifecycleScope.launch {
            com.fakegps.mocklocation.hotspot.HotspotLocationServer.connectedClientsCount.collect { count ->
                if (!isFinishing && !isDestroyed) {
                    if (count > 0) {
                        binding.tvHotspotBadge.text = "SYNC BETA ($count)"
                        binding.ivHotspotBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.badge_success_text))
                    } else if (com.fakegps.mocklocation.hotspot.HotspotLocationServer.isServerRunning.value) {
                        binding.tvHotspotBadge.text = "HOTSPOT BETA"
                        binding.ivHotspotBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary_bright))
                    } else {
                        binding.tvHotspotBadge.text = "HOTSPOT BETA"
                        binding.ivHotspotBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
                    }
                }
            }
        }

        com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = true)

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2500L)
            val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.checkForUpdates(this@MainActivity)
            if (updateInfo.isUpdateAvailable && updateInfo.appUpdateInfo != null && !isFinishing && !isDestroyed) {
                com.fakegps.mocklocation.util.AppUpdateManager.startPlayUpdateFlow(this@MainActivity, updateInfo.appUpdateInfo)
            }
        }

        handleIncomingIntents(intent)
    }

    private fun handleIncomingIntents(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("open_overlay_permission", false)) {
            if (!PermissionHelper.canDrawOverlays(this)) {
                PermissionHelper.requestOverlayPermission(this)
            }
        }

        if (intent.getBooleanExtra("focus_search", false)) {
            binding.etAddressSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(binding.etAddressSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        if (intent.getBooleanExtra("OPEN_WEATHER_DIALOG", false)) {
            val state = viewModel.uiState.value
            WeatherBottomSheet(state.fixedLatitude, state.fixedLongitude).show(supportFragmentManager, "WEATHER_DIALOG")
        }

        if (intent.getBooleanExtra("OPEN_VPN_DIALOG", false)) {
            val initialTab = intent.getIntExtra("INITIAL_TAB", 0)
            IpChangerBottomSheet.newInstance(initialTab = initialTab).show(supportFragmentManager, "IP_CHANGER_DIALOG")
        }

        if (intent.getBooleanExtra("OPEN_HOTSPOT_DIALOG", false)) {
            com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.TAG)
        }

        if (intent.getBooleanExtra("OPEN_SESSION_EXTEND_DIALOG", false)) {
            com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this).show()
        }

        if (intent.getBooleanExtra("OPEN_SESSION_EXPIRED_DIALOG", false)) {
            com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this, isExpiredPrompt = true) {
                startFixedSpoofing()
            }.show()
        }

        if (intent.getBooleanExtra("OPEN_PREMIUM_DIALOG", false) || intent.getBooleanExtra("open_premium", false)) {
            com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.PremiumBottomSheet.TAG)
        }

        if (intent.getBooleanExtra("EXTRA_START_SPOTLIGHT_TOUR", false)) {
            binding.root.postDelayed({
                startInteractiveHomeSpotlightTour()
            }, 400L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntents(intent)
    }

    private var connectivityManager: android.net.ConnectivityManager? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread {
                viewModel.retryPendingRoadRouting()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MockLocationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (com.fakegps.mocklocation.util.ThemeColorManager.isThemeStale) {
            com.fakegps.mocklocation.util.ThemeColorManager.isThemeStale = false
            recreate()
            return
        }
        binding.mapView.onResume()
        binding.mapView.setTileSource(settingsPrefs.getOsmTileSource())
        applyMapPerspectiveMode(settingsPrefs.mapTileSource)
        viewModel.refreshPermissionStates()
        checkBatteryOptimizationOnFirstLaunch()
        renderGhostCloakBadge()
        applyDynamicThemeAccent()
        viewModel.requestWeatherUpdate(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude, forceRefresh = true)
        com.fakegps.mocklocation.billing.BillingManager.getInstance(this).onResume()
        if (com.fakegps.mocklocation.data.preferences.SessionPreferences(this).hasValidActiveSession()) {
            com.fakegps.mocklocation.service.SessionTimerManager.resumeExistingTimer(this)
        }
        if (!com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value) {
            if (binding.adBannerContainer.childCount == 0) {
                com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = true)
            }
        } else {
            com.fakegps.mocklocation.ads.AdManager.clearBanner(binding.adBannerContainer)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun requestInitialPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupTouchIsolation() {
        // Prevent touches on overlay cards and menus from bleeding into the underlying MapView
        val consumeTouch = View.OnTouchListener { _, _ -> true }
        binding.cardBottomContainer.setOnTouchListener(consumeTouch)
        binding.layoutBottomContainer.setOnTouchListener(consumeTouch)
        binding.cardSideButtons.setOnTouchListener(consumeTouch)
        binding.cardTopBrandBar.setOnTouchListener(consumeTouch)
        binding.cardSearchBar.setOnTouchListener(consumeTouch)
    }

    private fun checkBatteryOptimizationOnFirstLaunch() {
        val prefs = SessionPreferences(this)
        if (!settingsPrefs.hasCompletedFeatureWalkthrough) {
            binding.root.postDelayed({
                startInteractiveHomeSpotlightTour {
                    if (!prefs.hasPromptedBatteryOptimization && !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
                        prefs.hasPromptedBatteryOptimization = true
                        com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog(this).show()
                    }
                }
            }, 600L)
        } else if (!prefs.hasPromptedBatteryOptimization && !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            prefs.hasPromptedBatteryOptimization = true
            com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog(this).show()
        } else if (!prefs.hasPromptedExactAlarmPermission && !PermissionHelper.canScheduleExactAlarms(this)) {
            prefs.hasPromptedExactAlarmPermission = true
            com.fakegps.mocklocation.ui.dialogs.ExactAlarmPermissionDialog(this).show()
        }
    }

    private fun startInteractiveHomeSpotlightTour(onFinished: (() -> Unit)? = null) {
        val steps = listOf(
            SpotlightStep(
                targetViewProvider = { binding.cardSearchBar },
                title = "Address & Coordinates Search",
                description = "Type any global city, address, or exact GPS coordinates (e.g. 37.7749, -122.4194) to immediately center the target pin.",
                iconRes = R.drawable.ic_search,
                stepNumber = 1,
                totalSteps = 5,
                paddingDp = 6f
            ),
            SpotlightStep(
                targetViewProvider = { binding.scrollQuickChips },
                title = "Quick City Presets",
                description = "1-tap teleport shortcuts to famous world cities (Paris, New York, Tokyo, Dubai, London, Honolulu) without typing.",
                iconRes = R.drawable.ic_location_pin,
                stepNumber = 2,
                totalSteps = 5,
                paddingDp = 6f
            ),
            SpotlightStep(
                targetViewProvider = { binding.cardTopBrandBar },
                title = "Security HUD & Live Telemetry",
                description = "Monitor your WireGuard IP Shield, Ghost Cloak anti-detection suite, local weather radar, and active session countdown timer.",
                iconRes = R.drawable.ic_shield_check,
                stepNumber = 3,
                totalSteps = 5,
                paddingDp = 6f
            ),
            SpotlightStep(
                targetViewProvider = { binding.cardSideButtons },
                title = "Floating Quick Tools",
                description = "Instant access to zoom controls, Center On Target, Bookmark Favorites, Simulation History, and Map Layer selection.",
                iconRes = R.drawable.ic_layers,
                stepNumber = 4,
                totalSteps = 5,
                paddingDp = 6f
            ),
            SpotlightStep(
                targetViewProvider = {
                    setBottomDeckExpanded(true)
                    binding.cardBottomContainer
                },
                title = "Control Deck & Simulation Modes",
                description = "Switch between Fixed Pin teleport, Multi-Point Route Simulation, and 360° Floating Joystick. Tap Start Mocking to inject GPS globally across all apps.",
                iconRes = R.drawable.ic_route,
                stepNumber = 5,
                totalSteps = 5,
                paddingDp = 4f
            )
        )

        binding.spotlightTourOverlay.startTour(steps) {
            settingsPrefs.hasCompletedFeatureWalkthrough = true
            onFinished?.invoke()
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setTileSource(settingsPrefs.getOsmTileSource())
            setMultiTouchControls(true)
            setTilesScaledToDpi(true) // Crisp retina high-DPI map rendering
            isHorizontalMapRepetitionEnabled = true
            isVerticalMapRepetitionEnabled = false
            isFlingEnabled = true
            maxZoomLevel = 21.0
            minZoomLevel = 3.0
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            val initialPoint = GeoPoint(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude)
            controller.setCenter(initialPoint)
        }

        val rotationOverlay = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(binding.mapView).apply {
            isEnabled = true
        }
        binding.mapView.overlays.add(rotationOverlay)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTapped(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onMapTapped(p.latitude, p.longitude)
                return true
            }
        })
        binding.mapView.overlays.add(0, mapEventsOverlay)

        applyMapPerspectiveMode(settingsPrefs.mapTileSource)

        var doubleTapDetectedTime = 0L
        var isOneHandedZoomActive = false
        var lastTouchY = 0f

        val oneHandedGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                doubleTapDetectedTime = System.currentTimeMillis()
                return false
            }
        })

        binding.mapView.setOnTouchListener { v, event ->
            oneHandedGestureDetector.onTouchEvent(event)

            if (event.pointerCount == 1) {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val timeSinceDoubleTap = System.currentTimeMillis() - doubleTapDetectedTime
                        if (timeSinceDoubleTap < 380L && doubleTapDetectedTime > 0L) {
                            isOneHandedZoomActive = true
                            lastTouchY = event.y
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (isOneHandedZoomActive) {
                            val deltaY = lastTouchY - event.y
                            if (Math.abs(deltaY) > 2f) {
                                val zoomDelta = deltaY * 0.0025
                                val currentZoom = binding.mapView.zoomLevelDouble
                                val newZoom = (currentZoom + zoomDelta).coerceIn(3.0, 21.0)
                                binding.mapView.controller.setZoom(newZoom)
                                lastTouchY = event.y
                            }
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isOneHandedZoomActive) {
                            isOneHandedZoomActive = false
                            doubleTapDetectedTime = 0L
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener true
                        }
                    }
                }
            } else {
                isOneHandedZoomActive = false
            }

            false
        }

        updateFixedPinMarker(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude)
    }

    private fun applyMapPerspectiveMode(sourceKey: String) {
        if (sourceKey == "3D_VECTOR" || sourceKey == "MAPLIBRE_3D" || sourceKey == "CARTO_3D") {
            val dm = resources.displayMetrics
            binding.mapView.cameraDistance = dm.density * 7000f
            binding.mapView.pivotX = dm.widthPixels / 2f
            binding.mapView.pivotY = dm.heightPixels * 0.85f
            binding.mapView.scaleX = 1.38f
            binding.mapView.scaleY = 1.38f
            binding.mapView.translationY = -dm.heightPixels * 0.12f
            binding.mapView.rotationX = 35f
        } else {
            binding.mapView.scaleX = 1.0f
            binding.mapView.scaleY = 1.0f
            binding.mapView.translationY = 0f
            binding.mapView.rotationX = 0f
        }
    }

    private fun onMapTapped(latitude: Double, longitude: Double) {
        // If search dropdown is visible, dismiss it cleanly on map tap
        if (binding.rvSearchResults.visibility == View.VISIBLE || binding.etAddressSearch.hasFocus()) {
            binding.rvSearchResults.visibility = View.GONE
            binding.etAddressSearch.clearFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etAddressSearch.windowToken, 0)
        }

        // Screen taps while mock simulation is running must NOT change or disrupt active mock location
        if (viewModel.uiState.value.isServiceRunning) {
            return
        }

        when (viewModel.uiState.value.selectedTab) {
            SelectedModeTab.FIXED -> {
                viewModel.setFixedCoordinates(latitude, longitude)
                updateFixedPinMarker(latitude, longitude)
            }
            SelectedModeTab.ROUTE -> {
                viewModel.addRouteWaypoint(latitude, longitude)
            }
            SelectedModeTab.JOYSTICK -> {
                viewModel.setFixedCoordinates(latitude, longitude)
                updateFixedPinMarker(latitude, longitude)
            }
        }
    }

    private fun setupSearch() {
        unifiedSearchAdapter = UnifiedSearchAdapter(
            onEntryClicked = { title, snippet, lat, lon ->
                val geoPoint = GeoPoint(lat, lon)
                if (settingsPrefs.enableMapAnimations) {
                    binding.mapView.controller.animateTo(geoPoint)
                } else {
                    binding.mapView.controller.setCenter(geoPoint)
                }
                binding.mapView.controller.setZoom(16.5)
                viewModel.setFixedCoordinates(lat, lon)
                updateFixedPinMarker(lat, lon)

                if (viewModel.uiState.value.isServiceRunning && viewModel.uiState.value.selectedTab == SelectedModeTab.FIXED) {
                    mockService?.startFixed(lat, lon)
                    val intent = Intent(this@MainActivity, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_FIXED
                        putExtra(MockLocationService.EXTRA_LATITUDE, lat)
                        putExtra(MockLocationService.EXTRA_LONGITUDE, lon)
                    }
                    startForegroundServiceCompat(intent)
                }

                viewModel.recordSearchHistory(
                    query = binding.etAddressSearch.text.toString().trim().ifBlank { title },
                    title = title,
                    snippet = snippet,
                    latitude = lat,
                    longitude = lon
                )

                val sessionPrefs = SessionPreferences(this@MainActivity)
                sessionPrefs.lastLocationName = title
                com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.updateAllWidgets(this@MainActivity)

                binding.rvSearchResults.visibility = View.GONE
                binding.etAddressSearch.setText(title)
                binding.etAddressSearch.setSelection(title.length)
                binding.etAddressSearch.clearFocus()
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etAddressSearch.windowToken, 0)
                Toast.makeText(this@MainActivity, "Target: $title", Toast.LENGTH_SHORT).show()
            },
            onDeleteHistoryClicked = { item ->
                viewModel.deleteSearchHistoryItem(item)
                latestHistoryItems = latestHistoryItems.filter { it.id != item.id }
                if (latestHistoryItems.isEmpty()) {
                    binding.rvSearchResults.visibility = View.GONE
                }
            }
        )

        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = unifiedSearchAdapter
        }

        // Real-time search suggestions with history fallback
        binding.etAddressSearch.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

            if (query.isNotEmpty()) {
                viewModel.searchAddress(query)
            } else {
                viewModel.clearSearchResults()
                showRecentHistory()
            }
        }

        binding.etAddressSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.etAddressSearch.text.isNullOrBlank()) {
                showRecentHistory()
            }
        }

        binding.etAddressSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.searchAddress(binding.etAddressSearch.text.toString().trim())
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etAddressSearch.windowToken, 0)
                true
            } else false
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etAddressSearch.setText("")
            viewModel.clearSearchResults()
            showRecentHistory()
        }

        binding.btnCopyCoords.setOnClickListener {
            val coordsText = binding.tvFixedCoords.text.toString()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Mock Location Coordinates", coordsText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "Coordinates copied: $coordsText", Toast.LENGTH_SHORT).show()
        }

        // Quick Preset Destination Chips
        binding.chipPresetNewYork.setOnClickListener { selectPresetDestination("New York, USA", 40.7128, -74.0060) }
        binding.chipPresetParis.setOnClickListener { selectPresetDestination("Paris, France", 48.8566, 2.3522) }
        binding.chipPresetTokyo.setOnClickListener { selectPresetDestination("Tokyo, Japan", 35.6762, 139.6503) }
        binding.chipPresetDubai.setOnClickListener { selectPresetDestination("Dubai, UAE", 25.2048, 55.2708) }
        binding.chipPresetLondon.setOnClickListener { selectPresetDestination("London, UK", 51.5074, -0.1278) }
        binding.chipPresetHonolulu.setOnClickListener { selectPresetDestination("Honolulu, Hawaii", 21.3069, -157.8583) }

        lifecycleScope.launch {
            viewModel.recentSearches.collectLatest { history ->
                latestHistoryItems = history
                if (binding.rvSearchResults.visibility == View.VISIBLE && binding.etAddressSearch.text.isNullOrBlank()) {
                    showRecentHistory()
                }
            }
        }
    }

    private fun selectPresetDestination(name: String, lat: Double, lon: Double) {
        val geoPoint = GeoPoint(lat, lon)
        if (settingsPrefs.enableMapAnimations) {
            binding.mapView.controller.animateTo(geoPoint)
        } else {
            binding.mapView.controller.setCenter(geoPoint)
        }
        binding.mapView.controller.setZoom(16.0)
        viewModel.setFixedCoordinates(lat, lon)
        updateFixedPinMarker(lat, lon)

        val sessionPrefs = SessionPreferences(this)
        sessionPrefs.lastLocationName = name
        com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.updateAllWidgets(this)

        Toast.makeText(this, "Focused: $name", Toast.LENGTH_SHORT).show()
    }

    private fun showRecentHistory() {
        if (latestHistoryItems.isNotEmpty()) {
            val entries = latestHistoryItems.map { SearchEntry.History(it) }
            unifiedSearchAdapter.submitEntries(entries)
            binding.rvSearchResults.visibility = View.VISIBLE
        } else {
            binding.rvSearchResults.visibility = View.GONE
        }
    }

    private fun setupModeTabs() {
        binding.rgModeTabs.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbFixedMode -> viewModel.setSelectedTab(SelectedModeTab.FIXED)
                R.id.rbRouteMode -> viewModel.setSelectedTab(SelectedModeTab.ROUTE)
                R.id.rbJoystickMode -> viewModel.setSelectedTab(SelectedModeTab.JOYSTICK)
            }
        }
    }

    private fun setupControls() {
        binding.btnFixedToggle.setOnClickListener {
            if (viewModel.uiState.value.isServiceRunning) {
                stopSpoofing()
            } else {
                startFixedSpoofing()
            }
        }

        binding.sliderRouteSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setRouteSpeed(value)
                mockService?.updateRouteSpeed(value)
            }
        }

        binding.switchLoopRoute.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                viewModel.setRouteLooping(isChecked)
                mockService?.updateRouteLooping(isChecked)
            }
        }

        binding.sliderJoystickSpeed.addOnChangeListener { _, value, _ ->
            viewModel.setJoystickSpeed(value)
        }

        binding.btnJoystickToggle.setOnClickListener {
            if (viewModel.uiState.value.isServiceRunning) {
                stopSpoofing()
            } else {
                startJoystickSpoofing()
            }
        }

        binding.btnFloatingOverlayToggle.setOnClickListener {
            if (!PermissionHelper.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Display over other apps' to enable floating joystick", Toast.LENGTH_LONG).show()
                PermissionHelper.requestOverlayPermission(this)
                return@setOnClickListener
            }
            if (com.fakegps.mocklocation.service.FloatingJoystickService.isRunning) {
                com.fakegps.mocklocation.service.FloatingJoystickService.stop(this)
                Toast.makeText(this, "Floating joystick closed", Toast.LENGTH_SHORT).show()
            } else {
                com.fakegps.mocklocation.service.FloatingJoystickService.start(this)
                Toast.makeText(this, "Floating joystick activated", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBannerEnable.setOnClickListener {
            SetupGuideDialog(this) {
                PermissionHelper.openDeveloperSettings(this)
            }.show()
        }

        binding.btnBannerErrorDismiss.setOnClickListener {
            viewModel.clearActiveError()
        }
    }

    private fun setupRouteActions() {
        binding.rgTransportMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbTransportFoot -> com.fakegps.mocklocation.simulator.TransportMode.FOOT
                R.id.rbTransportAircraft -> com.fakegps.mocklocation.simulator.TransportMode.AIRCRAFT
                R.id.rbTransportShip -> com.fakegps.mocklocation.simulator.TransportMode.SHIP
                else -> com.fakegps.mocklocation.simulator.TransportMode.VEHICLE
            }
            viewModel.setTransportMode(mode)
        }

        binding.btnManageWaypoints.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.WaypointManagerBottomSheet()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.WaypointManagerBottomSheet.TAG)
        }

        binding.btnImportGpx.setOnClickListener {
            gpxPickerLauncher.launch("*/*")
        }

        binding.btnExportGpx.setOnClickListener {
            exportCurrentRouteGpx()
        }

        binding.btnRouteUndo.setOnClickListener {
            viewModel.undoRouteWaypoint()
        }

        binding.btnRouteRedo.setOnClickListener {
            viewModel.redoRouteWaypoint()
        }

        binding.btnClearRoute.setOnClickListener {
            viewModel.clearRouteWaypoints()
        }

        binding.btnReverseRoute.setOnClickListener {
            viewModel.reverseRouteWaypoints()
        }

        binding.btnSaveCurrentRoute.setOnClickListener {
            val waypoints = viewModel.uiState.value.routeWaypoints
            if (waypoints.size < 2) {
                Toast.makeText(this, "Plot at least 2 waypoints to save a route.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var dist = 0.0
            for (i in 0 until waypoints.size - 1) {
                dist += GeoUtils.calculateDistanceMeters(
                    waypoints[i].latitude, waypoints[i].longitude,
                    waypoints[i + 1].latitude, waypoints[i + 1].longitude
                )
            }
            SaveRouteDialog(this, waypoints.size, dist) { name ->
                viewModel.saveCurrentRoute(name)
            }.show()
        }

        binding.btnSavedRoutesDrawer.setOnClickListener {
            SavedRoutesBottomSheet { route ->
                viewModel.loadSavedRoute(route)
            }.show(supportFragmentManager, SavedRoutesBottomSheet.TAG)
        }

        binding.btnRouteToggle.setOnClickListener {
            if (viewModel.uiState.value.isServiceRunning) {
                stopSpoofing()
            } else {
                startRouteSpoofing()
            }
        }

        binding.btnRoutePause.setOnClickListener {
            val isPaused = (viewModel.uiState.value.serviceState as? ServiceState.Running)?.isPaused == true
            if (isPaused) {
                mockService?.resumeRoute()
            } else {
                mockService?.pauseRoute()
            }
        }
    }

    private fun exportCurrentRouteGpx() {
        val state = viewModel.uiState.value
        val waypoints = if (state.userKeypoints.size >= 2) state.userKeypoints else state.routeWaypoints
        if (waypoints.size < 2) {
            Toast.makeText(this, "Plot at least 2 waypoints to export a GPX file.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gpxContent = viewModel.exportCurrentRouteToGpx("Nowhere Route (${waypoints.size} pts)")
            val exportFile = java.io.File(cacheDir, "nowhere_route_${System.currentTimeMillis()}.gpx")
            exportFile.writeText(gpxContent)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                exportFile
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Nowhere GPX Route")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Export GPX Route"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to export GPX: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupJoystick() {
        binding.joystickOverlay.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onJoystickMoved(angleDegrees: Float, magnitude: Float) {
                if (angleDegrees.isNaN() || magnitude.isNaN()) return
                val speedKmh = viewModel.uiState.value.joystickSpeedKmh
                if (!viewModel.uiState.value.isServiceRunning && magnitude > 0.02f) {
                    startJoystickSpoofing()
                }
                mockService?.updateJoystickVector(angleDegrees, magnitude, speedKmh)
                MockLocationServiceReceiver.sendJoystickUpdate(this@MainActivity, angleDegrees, magnitude, speedKmh)
            }
        })
    }

    private fun setBottomDeckExpanded(expanded: Boolean) {
        try {
            android.transition.TransitionManager.beginDelayedTransition(
                binding.cardBottomContainer,
                android.transition.AutoTransition().apply {
                    duration = 200
                }
            )
        } catch (ignored: Exception) {}

        binding.layoutExpandableBottomControls.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.ivToggleBottomDeck.setImageResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        binding.tvToggleBottomDeckLabel.text = if (expanded) "Slide down or tap to hide" else "Slide up or tap to show"
    }

    private fun setupBottomDeckToggle() {
        binding.btnToggleBottomDeck.setOnClickListener {
            val isCurrentlyVisible = binding.layoutExpandableBottomControls.visibility == View.VISIBLE
            setBottomDeckExpanded(!isCurrentlyVisible)
        }

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                binding.btnToggleBottomDeck.performClick()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY: Float = e2.y - e1.y
                val diffX: Float = e2.x - e1.x
                if (Math.abs(diffY) > Math.abs(diffX)) {
                    if (diffY > 30f || velocityY > 250f) {
                        setBottomDeckExpanded(false)
                        return true
                    } else if (diffY < -30f || velocityY < -250f) {
                        setBottomDeckExpanded(true)
                        return true
                    }
                }
                return false
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                if (Math.abs(distanceY) > Math.abs(distanceX) && Math.abs(distanceY) > 20f) {
                    if (distanceY < -15f) {
                        setBottomDeckExpanded(false)
                        return true
                    } else if (distanceY > 15f) {
                        setBottomDeckExpanded(true)
                        return true
                    }
                }
                return false
            }
        })

        binding.btnToggleBottomDeck.setOnTouchListener { v, event ->
            if (gestureDetector.onTouchEvent(event)) {
                true
            } else {
                v.onTouchEvent(event)
            }
        }
    }

    private fun setupFloatingButtons() {
        val sessionPrefs = SessionPreferences(this)
        binding.switchPersistentInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        binding.switchPersistentInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Active (Survives phone restarts)" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Floating Quick Tools Setup
        binding.fabZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.fabZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.fabMyLocation.setOnClickListener {
            val state = viewModel.uiState.value
            val centerLat = if (state.isServiceRunning && state.serviceState is ServiceState.Running) {
                state.serviceState.latitude
            } else {
                state.fixedLatitude
            }
            val centerLon = if (state.isServiceRunning && state.serviceState is ServiceState.Running) {
                state.serviceState.longitude
            } else {
                state.fixedLongitude
            }
            val geoPoint = GeoPoint(centerLat, centerLon)
            val currentZoom = binding.mapView.zoomLevelDouble
            if (currentZoom < 16.0) {
                // If zoomed out, smoothly animate position AND zoom in to street level (16.5) just like Google Maps
                binding.mapView.controller.animateTo(geoPoint, 16.5, 850L)
            } else {
                if (settingsPrefs.enableMapAnimations) {
                    binding.mapView.controller.animateTo(geoPoint)
                } else {
                    binding.mapView.controller.setCenter(geoPoint)
                }
            }
            Toast.makeText(this, "Target centered", Toast.LENGTH_SHORT).show()
        }

        binding.fabSaveFavorite.setOnClickListener {
            val state = viewModel.uiState.value
            SaveFavoriteDialog(this, state.fixedLatitude, state.fixedLongitude) { name, tag ->
                viewModel.saveFavorite(name, state.fixedLatitude, state.fixedLongitude, tag)
                com.fakegps.mocklocation.util.AppReviewManager.recordSuccessfulAction(this)
                Toast.makeText(this, "Saved to bookmarks", Toast.LENGTH_SHORT).show()
            }.show()
        }

        binding.fabOpenFavorites.setOnClickListener {
            FavoritesBottomSheet { favorite ->
                viewModel.setFixedCoordinates(favorite.latitude, favorite.longitude)
                updateFixedPinMarker(favorite.latitude, favorite.longitude)
                val geoPoint = GeoPoint(favorite.latitude, favorite.longitude)
                if (settingsPrefs.enableMapAnimations) {
                    binding.mapView.controller.animateTo(geoPoint)
                } else {
                    binding.mapView.controller.setCenter(geoPoint)
                }
                Toast.makeText(this, "Target set: ${favorite.name}", Toast.LENGTH_SHORT).show()
            }.show(supportFragmentManager, FavoritesBottomSheet.TAG)
        }

        binding.fabOpenHistory.setOnClickListener {
            HistoryBottomSheet(
                onReuseLocation = { lat, lon, name ->
                    viewModel.setFixedCoordinates(lat, lon)
                    updateFixedPinMarker(lat, lon)
                    val geoPoint = GeoPoint(lat, lon)
                    if (settingsPrefs.enableMapAnimations) {
                        binding.mapView.controller.animateTo(geoPoint)
                    } else {
                        binding.mapView.controller.setCenter(geoPoint)
                    }
                    viewModel.setSelectedTab(SelectedModeTab.FIXED)
                    startFixedSpoofing()
                    Toast.makeText(this, "Teleporting to $name", Toast.LENGTH_SHORT).show()
                },
                onReuseRoute = { routeHistory ->
                    try {
                        val array = org.json.JSONArray(routeHistory.waypointsJson)
                        val pts = mutableListOf<RoutePoint>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            pts.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lon")))
                        }
                        val mode = try {
                            com.fakegps.mocklocation.simulator.TransportMode.valueOf(routeHistory.transportMode)
                        } catch (e: Exception) {
                            com.fakegps.mocklocation.simulator.TransportMode.VEHICLE
                        }
                        viewModel.setLoadedRoute(pts, mode, routeHistory.speedKmh)
                        if (pts.isNotEmpty()) {
                            val geoPoint = GeoPoint(pts.first().latitude, pts.first().longitude)
                            binding.mapView.controller.setCenter(geoPoint)
                            binding.mapView.controller.setZoom(15.0)
                        }
                        Toast.makeText(this, "Loaded route: ${routeHistory.routeName}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to load route: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ).show(supportFragmentManager, "HISTORY_DIALOG")
        }

        binding.fabMapLayers.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.MapLayersBottomSheet { selectedSource ->
                applyMapPerspectiveMode(selectedSource)
                binding.mapView.setTileSource(settingsPrefs.getOsmTileSource())
                binding.mapView.invalidate()
                val label = when (selectedSource) {
                    "MAPNIK" -> "Standard Street Map"
                    "SATELLITE" -> "Satellite Hybrid"
                    "TOPO" -> "OpenTopo Terrain"
                    "3D_VECTOR" -> "3D Perspective Vector Map"
                    else -> "Standard Map"
                }
                Toast.makeText(this, "Map layer: $label", Toast.LENGTH_SHORT).show()
            }.show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.MapLayersBottomSheet.TAG)
        }

        binding.btnToggleSideMenu.setOnClickListener {
            performHapticFeedbackIfEnabled()
            val isExpanded = binding.layoutExtraSideTools.visibility == View.VISIBLE
            if (isExpanded) {
                binding.layoutExtraSideTools.visibility = View.GONE
                binding.btnToggleSideMenu.setImageResource(R.drawable.ic_chevron_down)
                binding.btnToggleSideMenu.contentDescription = "Expand Extra Tools"
            } else {
                binding.layoutExtraSideTools.visibility = View.VISIBLE
                binding.btnToggleSideMenu.setImageResource(R.drawable.ic_chevron_up)
                binding.btnToggleSideMenu.contentDescription = "Collapse Extra Tools"
            }
        }
    }

    private fun performHapticFeedbackIfEnabled() {
        if (!settingsPrefs.enableHapticFeedback) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun autoEngageVpnForLocation(lat: Double, lon: Double) {
        try {
            if (!settingsPrefs.isAutoVpnSyncEnabled) return
            val sessionPrefs = SessionPreferences(this)
            sessionPrefs.isIpMaskingEnabled = true
            val bestNode = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(lat, lon)
            sessionPrefs.activeIpNodeId = bestNode.id
            startVpnWithPermissionCheck(bestNode.id)
        } catch (ignored: Exception) {}
    }

    private fun ensureActiveSessionOrPrompt(onActive: () -> Unit): Boolean {
        val sessionPrefs = SessionPreferences(this)
        if (sessionPrefs.sessionExpiresTimestamp == 0L) {
            sessionPrefs.startNewSession(SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)
            return true
        }
        if (!sessionPrefs.hasValidActiveSession()) {
            com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this, isExpiredPrompt = true) {
                onActive()
            }.show()
            return false
        }
        return true
    }

    private fun startFixedSpoofing() {
        if (!verifyMockAppSelected()) return
        if (!ensureActiveSessionOrPrompt { startFixedSpoofing() }) return
        checkNotificationPermissionBeforeSimulation {
            performHapticFeedbackIfEnabled()

            val state = viewModel.uiState.value
            viewModel.recordLocationHistory(state.fixedLatitude, state.fixedLongitude, mode = "TELEPORT")
            autoEngageVpnForLocation(state.fixedLatitude, state.fixedLongitude)

            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_FIXED
                putExtra(MockLocationService.EXTRA_LATITUDE, state.fixedLatitude)
                putExtra(MockLocationService.EXTRA_LONGITUDE, state.fixedLongitude)
            }
            startForegroundServiceCompat(intent)
            mockService?.startFixed(state.fixedLatitude, state.fixedLongitude)
            com.fakegps.mocklocation.util.AppReviewManager.recordSuccessfulAction(this)
        }
    }

    private fun startRouteSpoofing() {
        if (!verifyMockAppSelected()) return
        if (!ensureActiveSessionOrPrompt { startRouteSpoofing() }) return
        checkNotificationPermissionBeforeSimulation {
            performHapticFeedbackIfEnabled()

            val state = viewModel.uiState.value
            val effectiveWaypoints = if (state.routeWaypoints.size >= 2) state.routeWaypoints else state.userKeypoints
            if (effectiveWaypoints.size < 2) {
                Toast.makeText(this, "Plot at least 2 waypoints by tapping the map or importing a GPX file.", Toast.LENGTH_LONG).show()
                return@checkNotificationPermissionBeforeSimulation
            }

            if (state.transportMode == com.fakegps.mocklocation.simulator.TransportMode.SHIP) {
                Toast.makeText(this, "Validating marine waters...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val (isValid, reason) = com.fakegps.mocklocation.simulator.RoadRouter.validateMarineRoute(this@MainActivity, effectiveWaypoints)
                    withContext(Dispatchers.Main) {
                        if (!isValid) {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("⚓ Ship Cannot Sail on Land")
                                .setMessage("Ships and marine vessels can only operate in water (oceans, seas, lakes, rivers).\n\n${reason ?: "One or more route waypoints are on land."}\n\nPlease reposition your waypoints into a water body or switch to Vehicle / Foot / Aircraft mode.")
                                .setPositiveButton("Reposition Waypoints", null)
                                .setNegativeButton("Switch to Vehicle") { _, _ ->
                                    viewModel.setTransportMode(com.fakegps.mocklocation.simulator.TransportMode.VEHICLE)
                                }
                                .show()
                        } else {
                            launchRouteService(state)
                        }
                    }
                }
            } else {
                launchRouteService(state)
            }
        }
    }

    private fun launchRouteService(state: MainUiState) {
        val waypointsToRun = if (state.routeWaypoints.size >= 2) state.routeWaypoints else state.userKeypoints
        val sessionPrefs = SessionPreferences(this)
        sessionPrefs.saveWaypoints(waypointsToRun)
        sessionPrefs.lastSpeedKmh = state.routeSpeedKmh
        sessionPrefs.isLooping = state.isRouteLooping

        var totalDist = 0.0
        for (i in 0 until waypointsToRun.size - 1) {
            totalDist += GeoUtils.calculateDistanceMeters(
                waypointsToRun[i].latitude, waypointsToRun[i].longitude,
                waypointsToRun[i + 1].latitude, waypointsToRun[i + 1].longitude
            )
        }
        viewModel.recordRouteHistory(
            routeName = "Route (${waypointsToRun.size} pts)",
            waypoints = waypointsToRun,
            totalDistanceMeters = totalDist,
            speedKmh = state.routeSpeedKmh,
            isLooping = state.isRouteLooping,
            transportMode = state.transportMode.name
        )

        autoEngageVpnForLocation(waypointsToRun.first().latitude, waypointsToRun.first().longitude)

        mockService?.startRoute(
            waypoints = waypointsToRun,
            speedKmh = state.routeSpeedKmh,
            isLooping = state.isRouteLooping,
            transportMode = state.transportMode
        )

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_SPEED_KMH, state.routeSpeedKmh)
            putExtra(MockLocationService.EXTRA_IS_LOOPING, state.isRouteLooping)
            putExtra(MockLocationService.EXTRA_TRANSPORT_MODE, state.transportMode.name)
        }
        startForegroundServiceCompat(intent)
        com.fakegps.mocklocation.util.AppReviewManager.recordSuccessfulAction(this)
    }

    private fun startJoystickSpoofing() {
        if (!verifyMockAppSelected()) return
        if (!ensureActiveSessionOrPrompt { startJoystickSpoofing() }) return
        checkNotificationPermissionBeforeSimulation {
            performHapticFeedbackIfEnabled()

            val state = viewModel.uiState.value
            viewModel.recordLocationHistory(state.fixedLatitude, state.fixedLongitude, mode = "JOYSTICK")
            autoEngageVpnForLocation(state.fixedLatitude, state.fixedLongitude)

            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_JOYSTICK
                putExtra(MockLocationService.EXTRA_LATITUDE, state.fixedLatitude)
                putExtra(MockLocationService.EXTRA_LONGITUDE, state.fixedLongitude)
                putExtra(MockLocationService.EXTRA_SPEED_KMH, state.joystickSpeedKmh)
            }
            startForegroundServiceCompat(intent)
            mockService?.startJoystick(state.fixedLatitude, state.fixedLongitude, state.joystickSpeedKmh)
            com.fakegps.mocklocation.util.AppReviewManager.recordSuccessfulAction(this)
        }
    }

    private fun stopSpoofing() {
        performHapticFeedbackIfEnabled()
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        startService(intent)
        mockService?.stopSpoofing()
        com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this)
        com.fakegps.mocklocation.ads.AdManager.showInterstitialIfReady(this)
    }

    private fun verifyMockAppSelected(): Boolean {
        val isMockEnabled = PermissionHelper.isMockLocationEnabled(this)
        if (!isMockEnabled) {
            SetupGuideDialog(this) {
                PermissionHelper.openDeveloperSettings(this)
            }.show()
            return false
        }
        return true
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun setupIpShield() {
        binding.layoutIpShieldBadge.setOnClickListener {
            val bottomSheet = com.fakegps.mocklocation.ui.dialogs.IpChangerBottomSheet(
                currentMockLat = viewModel.uiState.value.fixedLatitude,
                currentMockLon = viewModel.uiState.value.fixedLongitude
            )
            bottomSheet.show(supportFragmentManager, "IpChangerBottomSheet")
        }

        lifecycleScope.launch {
            com.fakegps.mocklocation.vpn.NowhereVpnService.vpnState.collectLatest { vpnState ->
                when (vpnState) {
                    is com.fakegps.mocklocation.vpn.NowhereVpnService.VpnState.Connected -> {
                        binding.layoutIpShieldBadge.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this@MainActivity)
                        binding.ivShieldIcon.imageTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this@MainActivity)
                        binding.tvIpShieldBadge.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this@MainActivity))
                        binding.tvIpShieldBadge.text = "${vpnState.node.flagEmoji} ${vpnState.node.countryCode}"
                    }
                    is com.fakegps.mocklocation.vpn.NowhereVpnService.VpnState.Connecting -> {
                        binding.tvIpShieldBadge.text = "IP: ..."
                    }
                    else -> {
                        binding.layoutIpShieldBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.surface_elevated)
                        binding.ivShieldIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.text_muted)
                        binding.tvIpShieldBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                        binding.tvIpShieldBadge.text = "IP: DIRECT"
                    }
                }
            }
        }
    }

    private fun setupGhostCloak() {
        binding.layoutGhostCloakBadge.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.AntiDetectionBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.AntiDetectionBottomSheet.TAG)
        }
        renderGhostCloakBadge()
    }

    private fun renderGhostCloakBadge() {
        val isCloaked = settingsPrefs.isGhostCloakEnabled
        if (isCloaked) {
            binding.layoutGhostCloakBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_active_bg)
            binding.ivGhostCloakIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary_bright)
            binding.tvGhostCloakBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_bright))
            binding.tvGhostCloakBadge.text = "CLOAK BETA"
        } else {
            binding.layoutGhostCloakBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_elevated)
            binding.ivGhostCloakIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
            binding.tvGhostCloakBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvGhostCloakBadge.text = "RAW GPS"
        }
    }

    private fun applyDynamicThemeAccent() {
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)
        val primaryCsl = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
        val lightTintCsl = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)

        binding.tvRouteDistanceRemaining.setTextColor(primaryColor)
        binding.pbRouteLiveProgress.progressTintList = primaryCsl
        binding.tvWaypointsCount.setTextColor(primaryColor)

        binding.sliderRouteSpeed.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        binding.sliderRouteSpeed.trackActiveTintList = primaryCsl
        binding.sliderJoystickSpeed.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        binding.sliderJoystickSpeed.trackActiveTintList = primaryCsl

        // Dynamic Segmented Pill Backgrounds
        binding.rbFixedMode.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.rbRouteMode.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.rbJoystickMode.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)

        // Dynamic Map FAB icon tints
        binding.fabMyLocation.imageTintList = primaryCsl
        binding.fabMapLayers.imageTintList = primaryCsl
        binding.fabSaveFavorite.imageTintList = primaryCsl
        binding.fabOpenFavorites.imageTintList = primaryCsl
        binding.fabOpenHistory.imageTintList = primaryCsl
        binding.ivSearchIcon.imageTintList = primaryCsl

        // Dynamic Joystick Colors
        binding.joystickOverlay.setJoystickColor(primaryColor)

        binding.rbTransportFoot.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.rbTransportVehicle.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.rbTransportAircraft.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.rbTransportShip.background = com.fakegps.mocklocation.util.ThemeColorManager.createSegmentedPillDrawable(primaryColor)
        binding.btnRoutePause.iconTint = primaryCsl

        // Dynamic Brand Logo
        val darkColor = com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this)
        binding.ivTopBrandLogo.setImageDrawable(com.fakegps.mocklocation.util.ThemeColorManager.getThemedLogoDrawable(this, primaryColor, darkColor))

        // Dynamic Map Target Pin
        fixedPinMarker?.icon = com.fakegps.mocklocation.util.ThemeColorManager.getThemedTargetPinDrawable(this, primaryColor, darkColor)

        // Dynamic Session Timer and Premium badges
        binding.layoutSessionTimerBadge.backgroundTintList = lightTintCsl
        binding.tvSessionTimerBadge.setTextColor(primaryColor)
        binding.ivSessionTimerIcon.imageTintList = primaryCsl
        binding.layoutPremiumBadge.backgroundTintList = lightTintCsl
        binding.tvPremiumBadge.setTextColor(primaryColor)
        binding.ivPremiumBadgeIcon.imageTintList = primaryCsl

        // Update Ghost Cloak badge if active
        if (settingsPrefs.isGhostCloakEnabled) {
            binding.layoutGhostCloakBadge.backgroundTintList = lightTintCsl
            binding.ivGhostCloakIcon.imageTintList = primaryCsl
            binding.tvGhostCloakBadge.setTextColor(primaryColor)
        }

        // Update Spotlight Tour overlay colors
        binding.spotlightTourOverlay.setTourColors(primaryColor, com.fakegps.mocklocation.util.ThemeColorManager.getGlowColor(this))

        // Recursively theme all other views across the screen (including switches and cards)
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, this)
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            com.fakegps.mocklocation.util.ThemeColorManager.themeChangeFlow.collectLatest {
                applyDynamicThemeAccent()
                renderUiState(viewModel.uiState.value)
                binding.mapView.invalidate()
            }
        }

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                renderUiState(state)
            }
        }

        lifecycleScope.launch {
            viewModel.weatherReport.collectLatest { report ->
                if (report != null && !isFinishing && !isDestroyed) {
                    binding.tvWeatherEmojiBadge.text = report.current.conditionEmoji
                    val tempStr = if (settingsPrefs.useImperialUnits) {
                        String.format(Locale.US, "%.0f°F", report.current.temperatureF)
                    } else {
                        String.format(Locale.US, "%.0f°C", report.current.temperatureC)
                    }
                    binding.tvWeatherBadgeText.text = tempStr
                    binding.layoutWeatherBadge.contentDescription = "${report.locationName}: ${report.current.conditionName}, $tempStr"
                }
            }
        }

        lifecycleScope.launch {
            com.fakegps.mocklocation.billing.BillingManager.getInstance(this@MainActivity).isPremium.collectLatest { isPremium ->
                if (!isFinishing && !isDestroyed) {
                    if (isPremium) {
                        binding.tvPremiumBadge.text = "PREMIUM"
                        binding.tvPremiumBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_success_text))
                        binding.ivPremiumBadgeIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_success_text)
                        binding.layoutPremiumBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_success_bg)
                        com.fakegps.mocklocation.ads.AdManager.clearBanner(binding.adBannerContainer)
                    } else {
                        val isVip = com.fakegps.mocklocation.billing.PromotionManager.isEligibleForVipDiscount(this@MainActivity)
                        binding.tvPremiumBadge.text = if (isVip) "PRO (15% OFF)" else "GET PRO"
                        binding.tvPremiumBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary_bright))
                        binding.ivPremiumBadgeIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.primary_bright)
                        binding.layoutPremiumBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_active_bg)
                        if (binding.adBannerContainer.childCount == 0) {
                            com.fakegps.mocklocation.ads.AdManager.loadBanner(this@MainActivity, binding.adBannerContainer, isHomeBanner = true)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            com.fakegps.mocklocation.service.SessionTimerManager.timerState.collectLatest { timerState ->
                if (!isFinishing && !isDestroyed) {
                    if (timerState.isUnlimited) {
                        binding.layoutSessionTimerBadge.visibility = View.VISIBLE
                        binding.layoutSessionTimerBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_success_bg)
                        binding.tvSessionTimerBadge.text = "UNLIMITED"
                        binding.tvSessionTimerBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_success_text))
                        binding.ivSessionTimerIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_success_text)
                    } else if (timerState.isRunning) {
                        binding.layoutSessionTimerBadge.visibility = View.VISIBLE
                        binding.layoutSessionTimerBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_active_bg)
                        binding.tvSessionTimerBadge.text = timerState.formattedRemaining
                        binding.tvSessionTimerBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary_bright))
                        binding.ivSessionTimerIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.primary_bright)
                    } else if (timerState.isExpired) {
                        binding.layoutSessionTimerBadge.visibility = View.VISIBLE
                        binding.layoutSessionTimerBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_error_bg)
                        binding.tvSessionTimerBadge.text = "EXPIRED"
                        binding.tvSessionTimerBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_error_text))
                        binding.ivSessionTimerIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_error_text)
                    } else {
                        binding.layoutSessionTimerBadge.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderUiState(state: MainUiState) {
        // Status Pill Badge
        if (state.isServiceRunning) {
            binding.tvStatusBadge.text = getString(R.string.status_mock_active)
            binding.tvStatusBadge.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
            binding.viewStatusDot.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
            binding.layoutStatusBadge.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_standby)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_standby_text))
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_text)
            binding.layoutStatusBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_bg)
        }

        // Warning & Error banners
        binding.bannerMockSetup.visibility = if (!state.isMockAppEnabled) View.VISIBLE else View.GONE
        if (state.activeError != null) {
            binding.bannerError.visibility = View.VISIBLE
            binding.tvBannerErrorText.text = state.activeError.message
        } else {
            binding.bannerError.visibility = View.GONE
        }

        // Tab UI visibility
        when (state.selectedTab) {
            SelectedModeTab.FIXED -> {
                binding.layoutFixedControls.visibility = View.VISIBLE
                binding.layoutRouteControls.visibility = View.GONE
                binding.layoutJoystickControls.visibility = View.GONE
                binding.joystickOverlay.visibility = View.GONE
                binding.rbFixedMode.isChecked = true
            }
            SelectedModeTab.ROUTE -> {
                binding.layoutFixedControls.visibility = View.GONE
                binding.layoutRouteControls.visibility = View.VISIBLE
                binding.layoutJoystickControls.visibility = View.GONE
                binding.joystickOverlay.visibility = View.GONE
                binding.rbRouteMode.isChecked = true
            }
            SelectedModeTab.JOYSTICK -> {
                binding.layoutFixedControls.visibility = View.GONE
                binding.layoutRouteControls.visibility = View.GONE
                binding.layoutJoystickControls.visibility = View.VISIBLE
                binding.joystickOverlay.visibility = if (state.isServiceRunning) View.VISIBLE else View.GONE
                binding.rbJoystickMode.isChecked = true
            }
        }

        // Telemetry Coordinates Readout
        val lat = if (state.isServiceRunning && state.serviceState is ServiceState.Running) state.serviceState.latitude else state.fixedLatitude
        val lon = if (state.isServiceRunning && state.serviceState is ServiceState.Running) state.serviceState.longitude else state.fixedLongitude
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        binding.tvFixedCoords.text = String.format("%.5f° %s, %.5f° %s", Math.abs(lat), latDir, Math.abs(lon), lonDir)

        if (state.isServiceRunning && state.serviceState is ServiceState.Running) {
            val speedKmh = state.serviceState.speedMps * 3.6f
            val formattedSpeed = settingsPrefs.formatSpeed(speedKmh)
            binding.tvTelemetryMeta.text = String.format("%s • ±%.1fm • 18 SAT", formattedSpeed, settingsPrefs.baseAccuracy)
        } else {
            binding.tvTelemetryMeta.text = String.format("READY • ±%.1fm • 18 SAT", settingsPrefs.baseAccuracy)
        }

        // Fixed Controls State
        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.FIXED) {
            binding.btnFixedToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnFixedToggle.setIconResource(R.drawable.ic_stop)
            binding.btnFixedToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
            binding.btnFixedToggle.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))
            binding.btnFixedToggle.iconTint = android.content.res.ColorStateList.valueOf(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))
        } else {
            binding.btnFixedToggle.text = getString(R.string.btn_start_teleport)
            binding.btnFixedToggle.setIconResource(R.drawable.ic_teleport)
            binding.btnFixedToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
            binding.btnFixedToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnFixedToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
        }

        // Route Controls State & Metrics
        var totalRouteDist = 0.0
        if (state.routeWaypoints.size >= 2) {
            for (i in 0 until state.routeWaypoints.size - 1) {
                totalRouteDist += GeoUtils.calculateDistanceMeters(
                    state.routeWaypoints[i].latitude, state.routeWaypoints[i].longitude,
                    state.routeWaypoints[i + 1].latitude, state.routeWaypoints[i + 1].longitude
                )
            }
        }
        val formattedDist = settingsPrefs.formatDistance(totalRouteDist)
        binding.tvWaypointsCount.text = "${state.routeWaypoints.size} Waypoints ($formattedDist)"
        binding.btnRouteUndo.isEnabled = state.canUndoRoute
        binding.btnRouteUndo.alpha = if (state.canUndoRoute) 1.0f else 0.35f
        binding.btnRouteRedo.isEnabled = state.canRedoRoute
        binding.btnRouteRedo.alpha = if (state.canRedoRoute) 1.0f else 0.35f

        binding.tvRouteSpeedLabel.text = settingsPrefs.formatSpeed(state.routeSpeedKmh)

        binding.sliderRouteSpeed.valueFrom = state.transportMode.minSpeedKmh
        binding.sliderRouteSpeed.valueTo = state.transportMode.maxSpeedKmh
        binding.sliderRouteSpeed.value = state.routeSpeedKmh.coerceIn(state.transportMode.minSpeedKmh, state.transportMode.maxSpeedKmh)
        binding.switchLoopRoute.isChecked = state.isRouteLooping

        when (state.transportMode) {
            com.fakegps.mocklocation.simulator.TransportMode.FOOT -> binding.rbTransportFoot.isChecked = true
            com.fakegps.mocklocation.simulator.TransportMode.AIRCRAFT -> binding.rbTransportAircraft.isChecked = true
            com.fakegps.mocklocation.simulator.TransportMode.SHIP -> binding.rbTransportShip.isChecked = true
            else -> binding.rbTransportVehicle.isChecked = true
        }


        val runningState = state.serviceState as? ServiceState.Running
        val effectiveWaypoints = if (state.routeWaypoints.size >= 2) state.routeWaypoints else state.userKeypoints

        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.ROUTE) {
            binding.btnRouteToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnRouteToggle.setIconResource(R.drawable.ic_stop)
            binding.btnRouteToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
            binding.btnRouteToggle.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))
            binding.btnRouteToggle.iconTint = android.content.res.ColorStateList.valueOf(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))
            binding.btnRoutePause.visibility = View.VISIBLE

            val isPaused = runningState?.isPaused == true
            binding.btnRoutePause.text = if (isPaused) getString(R.string.btn_resume_route) else getString(R.string.btn_pause_route)
            binding.btnRoutePause.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
            binding.btnRoutePause.iconTint = ContextCompat.getColorStateList(this, if (isPaused) R.color.badge_success_text else R.color.primary_bright)
            binding.btnRoutePause.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            binding.layoutRouteTelemetry.visibility = View.VISIBLE

            if (runningState != null && runningState.totalDistanceMeters > 0) {
                val covered = settingsPrefs.formatDistance(runningState.distanceCoveredMeters)
                val total = settingsPrefs.formatDistance(runningState.totalDistanceMeters)
                val remaining = settingsPrefs.formatDistance(runningState.distanceRemainingMeters)
                val progress = ((runningState.distanceCoveredMeters / runningState.totalDistanceMeters) * 100).toInt().coerceIn(0, 100)

                val speedMps = runningState.speedMps
                val remainingMeters = runningState.distanceRemainingMeters
                val etaText = if (speedMps > 0.2f && remainingMeters > 5.0) {
                    val secondsLeft = (remainingMeters / speedMps).toLong()
                    val hours = secondsLeft / 3600
                    val minutes = (secondsLeft % 3600) / 60
                    val seconds = secondsLeft % 60
                    if (hours > 0) {
                        String.format(Locale.US, "⏱️ ETA: %dh %02dm", hours, minutes)
                    } else {
                        String.format(Locale.US, "⏱️ ETA: %02dm %02ds", minutes, seconds)
                    }
                } else if (remainingMeters <= 5.0 && runningState.totalDistanceMeters > 0) {
                    "⏱️ ETA: Arrived"
                } else {
                    "⏱️ ETA: --"
                }

                binding.tvRouteDistanceCovered.text = "Covered: $covered / $total"
                binding.tvRouteEta.text = etaText
                binding.tvRouteDistanceRemaining.text = "$remaining left ($progress%)"
                binding.pbRouteLiveProgress.progress = progress
            }
        } else {
            binding.btnRouteToggle.text = getString(R.string.btn_start_route)
            binding.btnRouteToggle.setIconResource(R.drawable.ic_play)
            binding.btnRouteToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
            binding.btnRouteToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnRouteToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.btnRoutePause.visibility = View.GONE

            if (state.selectedTab == SelectedModeTab.ROUTE && effectiveWaypoints.size >= 2) {
                binding.layoutRouteTelemetry.visibility = View.VISIBLE
                var totalDist = 0.0
                for (i in 0 until effectiveWaypoints.size - 1) {
                    totalDist += GeoUtils.calculateDistanceMeters(
                        effectiveWaypoints[i].latitude, effectiveWaypoints[i].longitude,
                        effectiveWaypoints[i + 1].latitude, effectiveWaypoints[i + 1].longitude
                    )
                }
                val totalFormatted = settingsPrefs.formatDistance(totalDist)
                val speedKmh = state.routeSpeedKmh.coerceAtLeast(1.0f)
                val totalKm = totalDist / 1000.0
                val totalSeconds = ((totalKm / speedKmh) * 3600).toLong()
                val hrs = totalSeconds / 3600
                val mins = (totalSeconds % 3600) / 60
                val estTime = if (hrs > 0) {
                    String.format(Locale.US, "⏱️ Est: %dh %02dm", hrs, mins)
                } else {
                    String.format(Locale.US, "⏱️ Est: %dm", mins.coerceAtLeast(1))
                }

                binding.tvRouteDistanceCovered.text = "Route: $totalFormatted"
                binding.tvRouteEta.text = estTime
                binding.tvRouteDistanceRemaining.text = "${effectiveWaypoints.size} pts"
                binding.pbRouteLiveProgress.progress = 0
            } else {
                binding.layoutRouteTelemetry.visibility = View.GONE
            }
        }

        // Joystick Controls State
        binding.tvJoystickSpeedLabel.text = "MAX: " + settingsPrefs.formatSpeed(state.joystickSpeedKmh)
        binding.sliderJoystickSpeed.value = state.joystickSpeedKmh.coerceIn(2.0f, 60.0f)
        binding.joystickOverlay.visibility = if (state.selectedTab == SelectedModeTab.JOYSTICK) View.VISIBLE else View.GONE

        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.JOYSTICK) {
            binding.btnJoystickToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_stop)
            binding.btnJoystickToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(this)
            binding.btnJoystickToggle.setTextColor(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))
            binding.btnJoystickToggle.iconTint = android.content.res.ColorStateList.valueOf(com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this))

            val running = state.serviceState as? ServiceState.Running
            if (running != null && running.mode is SimulationMode.Joystick) {
                updateFixedPinMarker(running.latitude, running.longitude)
                binding.mapView.controller.setCenter(GeoPoint(running.latitude, running.longitude))
            }
        } else {
            binding.btnJoystickToggle.text = "Engage Joystick"
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_play)
            binding.btnJoystickToggle.backgroundTintList = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColorStateList(this)
            binding.btnJoystickToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnJoystickToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
        }

        // Live Search Results
        if (state.searchResults.isNotEmpty()) {
            val entries = state.searchResults.map {
                SearchEntry.LiveResult(it.title, it.snippet, it.latitude, it.longitude)
            }
            unifiedSearchAdapter.submitEntries(entries)
            binding.rvSearchResults.visibility = View.VISIBLE
        } else if (!binding.etAddressSearch.text.isNullOrBlank()) {
            binding.rvSearchResults.visibility = View.GONE
        }
        binding.pbSearchLoading.visibility = if (state.isSearching) View.VISIBLE else View.GONE

        if (state.statusMessage != null) {
            Toast.makeText(this, state.statusMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }

        renderMapOverlays(state)
    }

    private fun updateFixedPinMarker(latitude: Double, longitude: Double) {
        val geoPoint = GeoPoint(latitude, longitude)
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)
        val darkColor = com.fakegps.mocklocation.util.ThemeColorManager.getDarkColor(this)
        val themedPin = com.fakegps.mocklocation.util.ThemeColorManager.getThemedTargetPinDrawable(this, primaryColor, darkColor)
        if (fixedPinMarker == null) {
            fixedPinMarker = Marker(binding.mapView).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Target Location"
                icon = themedPin
            }
            binding.mapView.overlays.add(fixedPinMarker)
        } else {
            fixedPinMarker?.position = geoPoint
            fixedPinMarker?.icon = themedPin
        }
        binding.mapView.invalidate()
    }

    private fun renderMapOverlays(state: MainUiState) {
        if (state.selectedTab == SelectedModeTab.ROUTE) {
            for (m in routeMarkers) {
                binding.mapView.overlays.remove(m)
            }
            routeMarkers.clear()

            if (routePolyline != null) {
                binding.mapView.overlays.remove(routePolyline)
                routePolyline = null
            }

            val pathPoints = if (state.routeWaypoints.isNotEmpty()) {
                state.routeWaypoints
            } else if (state.userKeypoints.isNotEmpty()) {
                state.userKeypoints
            } else {
                emptyList()
            }

            if (pathPoints.isNotEmpty()) {
                val geoPoints = pathPoints.map { GeoPoint(it.latitude, it.longitude) }
                if (geoPoints.size >= 2) {
                    routePolyline = Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this@MainActivity)
                        outlinePaint.strokeWidth = 8f
                        setOnClickListener { _, _, _ -> false }
                    }
                    binding.mapView.overlays.add(routePolyline)
                }

                val waypointsToMark = if (state.userKeypoints.isNotEmpty()) {
                    state.userKeypoints.mapIndexed { idx, pt -> Pair(idx + 1, pt) }
                } else if (pathPoints.size <= 25) {
                    pathPoints.mapIndexed { idx, pt -> Pair(idx + 1, pt) }
                } else {
                    val sampled = mutableListOf<Pair<Int, com.fakegps.mocklocation.simulator.RoutePoint>>()
                    sampled.add(Pair(1, pathPoints.first()))
                    val step = (pathPoints.size - 2) / 10
                    if (step > 0) {
                        for (i in 1..10) {
                            val targetIndex = i * step
                            if (targetIndex < pathPoints.size - 1) {
                                sampled.add(Pair(targetIndex + 1, pathPoints[targetIndex]))
                            }
                        }
                    }
                    sampled.add(Pair(pathPoints.size, pathPoints.last()))
                    sampled
                }

                for ((labelIdx, wp) in waypointsToMark) {
                    val marker = Marker(binding.mapView).apply {
                        position = GeoPoint(wp.latitude, wp.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Waypoint #$labelIdx"
                        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_route)
                        setOnMarkerClickListener { _, _ -> false }
                    }
                    routeMarkers.add(marker)
                    binding.mapView.overlays.add(marker)
                }
            }
        } else {
            for (m in routeMarkers) {
                binding.mapView.overlays.remove(m)
            }
            routeMarkers.clear()
            if (routePolyline != null) {
                binding.mapView.overlays.remove(routePolyline)
                routePolyline = null
            }
        }

        if (state.isServiceRunning && state.serviceState is ServiceState.Running) {
            val liveState = state.serviceState
            val currentPoint = GeoPoint(liveState.latitude, liveState.longitude)

            updateFixedPinMarker(liveState.latitude, liveState.longitude)

            if (liveSimMarker == null) {
                liveSimMarker = Marker(binding.mapView).apply {
                    position = currentPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Nowhere Live Position"
                    icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_my_location)
                }
                binding.mapView.overlays.add(liveSimMarker)
            } else {
                liveSimMarker?.position = currentPoint
                liveSimMarker?.rotation = liveState.bearingDegrees
            }

            if (state.selectedTab == SelectedModeTab.JOYSTICK && liveState.speedMps > 0.05f) {
                binding.mapView.controller.setCenter(currentPoint)
            }
        } else {
            if (liveSimMarker != null) {
                binding.mapView.overlays.remove(liveSimMarker)
                liveSimMarker = null
            }
        }

        binding.mapView.invalidate()
    }

    override fun onDestroy() {
        try {
            binding.mapView.onDetach()
        } catch (ignored: Exception) {}
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            binding.mapView.tileProvider?.clearTileCache()
            System.gc()
        } catch (ignored: Exception) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            try {
                binding.mapView.tileProvider?.clearTileCache()
                System.gc()
            } catch (ignored: Exception) {}
        }
    }
}
