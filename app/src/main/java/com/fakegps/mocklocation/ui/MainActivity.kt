package com.fakegps.mocklocation.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.fakegps.mocklocation.ui.custom.JoystickView
import com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog
import com.fakegps.mocklocation.ui.dialogs.SaveFavoriteDialog
import com.fakegps.mocklocation.ui.dialogs.SaveRouteDialog
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.ui.favorites.FavoritesBottomSheet
import com.fakegps.mocklocation.ui.routes.SavedRoutesBottomSheet
import com.fakegps.mocklocation.util.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupSearch()
        setupModeTabs()
        setupControls()
        setupRouteActions()
        setupJoystick()
        setupFloatingButtons()
        requestInitialPermissions()
        observeUiState()

        binding.btnHeaderSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (intent?.getBooleanExtra("open_overlay_permission", false) == true) {
            if (!PermissionHelper.canDrawOverlays(this)) {
                PermissionHelper.requestOverlayPermission(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MockLocationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        binding.mapView.setTileSource(settingsPrefs.getOsmTileSource())
        viewModel.refreshPermissionStates()
        checkBatteryOptimizationOnFirstLaunch()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
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

    private fun checkBatteryOptimizationOnFirstLaunch() {
        val prefs = SessionPreferences(this)
        if (!prefs.hasPromptedBatteryOptimization && !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            prefs.hasPromptedBatteryOptimization = true
            BatteryOptimizationDialog(this).show()
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setTileSource(settingsPrefs.getOsmTileSource())
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            isHorizontalMapRepetitionEnabled = true
            isVerticalMapRepetitionEnabled = false
            isFlingEnabled = true
            maxZoomLevel = 21.0
            minZoomLevel = 3.0
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.5)
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

        updateFixedPinMarker(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude)
    }

    private fun onMapTapped(latitude: Double, longitude: Double) {
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

                viewModel.recordSearchHistory(
                    query = binding.etAddressSearch.text.toString().trim().ifBlank { title },
                    title = title,
                    snippet = snippet,
                    latitude = lat,
                    longitude = lon
                )

                binding.rvSearchResults.visibility = View.GONE
                binding.etAddressSearch.clearFocus()
            },
            onDeleteHistoryClicked = { item ->
                viewModel.deleteSearchHistoryItem(item)
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
                true
            } else false
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etAddressSearch.setText("")
            showRecentHistory()
        }

        lifecycleScope.launch {
            viewModel.recentSearches.collectLatest { history ->
                latestHistoryItems = history
                if (binding.etAddressSearch.text.isNullOrBlank() && binding.etAddressSearch.hasFocus()) {
                    showRecentHistory()
                }
            }
        }
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

        binding.sliderRouteSpeed.addOnChangeListener { _, value, _ ->
            viewModel.setRouteSpeed(value)
        }

        binding.switchLoopRoute.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRouteLooping(isChecked)
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

        binding.btnImportGpx.setOnClickListener {
            gpxPickerLauncher.launch("*/*")
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

    private fun setupJoystick() {
        binding.joystickOverlay.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onJoystickMoved(angleDegrees: Float, magnitude: Float) {
                val speedKmh = viewModel.uiState.value.joystickSpeedKmh
                if (!viewModel.uiState.value.isServiceRunning && magnitude > 0.05f) {
                    startJoystickSpoofing()
                }
                mockService?.updateJoystickVector(angleDegrees, magnitude, speedKmh)
                MockLocationServiceReceiver.sendJoystickUpdate(this@MainActivity, angleDegrees, magnitude, speedKmh)
            }
        })
    }

    private fun setupFloatingButtons() {
        val sessionPrefs = SessionPreferences(this)
        binding.switchPersistentInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        binding.switchPersistentInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Active (Survives phone restarts)" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

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
            if (settingsPrefs.enableMapAnimations) {
                binding.mapView.controller.animateTo(geoPoint)
            } else {
                binding.mapView.controller.setCenter(geoPoint)
            }
        }

        binding.fabSaveFavorite.setOnClickListener {
            val state = viewModel.uiState.value
            SaveFavoriteDialog(this, state.fixedLatitude, state.fixedLongitude) { name, tag ->
                viewModel.saveFavorite(name, state.fixedLatitude, state.fixedLongitude, tag)
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
    }

    private fun performHapticFeedbackIfEnabled() {
        if (!settingsPrefs.enableHapticFeedback) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(40)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun startFixedSpoofing() {
        if (!verifyMockAppSelected()) return
        performHapticFeedbackIfEnabled()

        val state = viewModel.uiState.value
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_FIXED
            putExtra(MockLocationService.EXTRA_LATITUDE, state.fixedLatitude)
            putExtra(MockLocationService.EXTRA_LONGITUDE, state.fixedLongitude)
        }
        startForegroundServiceCompat(intent)
        mockService?.startFixed(state.fixedLatitude, state.fixedLongitude)
    }

    private fun startRouteSpoofing() {
        if (!verifyMockAppSelected()) return
        performHapticFeedbackIfEnabled()

        val state = viewModel.uiState.value
        if (state.routeWaypoints.size < 2) {
            Toast.makeText(this, "Plot at least 2 waypoints by tapping the map or importing a GPX file.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_SPEED_KMH, state.routeSpeedKmh)
            putExtra(MockLocationService.EXTRA_IS_LOOPING, state.isRouteLooping)
            putExtra(MockLocationService.EXTRA_TRANSPORT_MODE, state.transportMode.name)
        }
        startForegroundServiceCompat(intent)
        mockService?.startRoute(state.routeWaypoints, state.routeSpeedKmh, state.isRouteLooping, state.transportMode)
    }

    private fun startJoystickSpoofing() {
        if (!verifyMockAppSelected()) return
        performHapticFeedbackIfEnabled()

        val state = viewModel.uiState.value
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_JOYSTICK
            putExtra(MockLocationService.EXTRA_LATITUDE, state.fixedLatitude)
            putExtra(MockLocationService.EXTRA_LONGITUDE, state.fixedLongitude)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, state.joystickSpeedKmh)
        }
        startForegroundServiceCompat(intent)
        mockService?.startJoystick(state.fixedLatitude, state.fixedLongitude, state.joystickSpeedKmh)
    }

    private fun stopSpoofing() {
        performHapticFeedbackIfEnabled()
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        startService(intent)
        mockService?.stopSpoofing()
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

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                renderUiState(state)
            }
        }
    }

    private fun renderUiState(state: MainUiState) {
        // Status Pill Badge
        if (state.isServiceRunning) {
            binding.tvStatusBadge.text = getString(R.string.status_mock_active)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_active_text)
            binding.layoutStatusBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_active_bg)
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
            binding.btnFixedToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.btnFixedToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnFixedToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
        } else {
            binding.btnFixedToggle.text = getString(R.string.btn_start_teleport)
            binding.btnFixedToggle.setIconResource(R.drawable.ic_teleport)
            binding.btnFixedToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
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

        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.ROUTE) {
            binding.btnRouteToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnRouteToggle.setIconResource(R.drawable.ic_stop)
            binding.btnRouteToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.btnRouteToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnRouteToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.btnRoutePause.visibility = View.VISIBLE

            val isPaused = (state.serviceState as? ServiceState.Running)?.isPaused == true
            binding.btnRoutePause.text = if (isPaused) getString(R.string.btn_resume_route) else getString(R.string.btn_pause_route)
            binding.btnRoutePause.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        } else {
            binding.btnRouteToggle.text = getString(R.string.btn_start_route)
            binding.btnRouteToggle.setIconResource(R.drawable.ic_play)
            binding.btnRouteToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            binding.btnRouteToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnRouteToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.btnRoutePause.visibility = View.GONE
        }

        // Joystick Controls State
        binding.tvJoystickSpeedLabel.text = "MAX: " + settingsPrefs.formatSpeed(state.joystickSpeedKmh)
        binding.sliderJoystickSpeed.value = state.joystickSpeedKmh.coerceIn(2.0f, 60.0f)
        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.JOYSTICK) {
            binding.btnJoystickToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_stop)
            binding.btnJoystickToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.btnJoystickToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnJoystickToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.joystickOverlay.visibility = View.VISIBLE
        } else {
            binding.btnJoystickToggle.text = "Engage Joystick"
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_play)
            binding.btnJoystickToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            binding.btnJoystickToggle.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnJoystickToggle.iconTint = ContextCompat.getColorStateList(this, R.color.white)
            binding.joystickOverlay.visibility = View.GONE
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
        if (fixedPinMarker == null) {
            fixedPinMarker = Marker(binding.mapView).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Target Location"
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_pin)
            }
            binding.mapView.overlays.add(fixedPinMarker)
        } else {
            fixedPinMarker?.position = geoPoint
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

            if (state.routeWaypoints.isNotEmpty()) {
                val geoPoints = state.routeWaypoints.map { GeoPoint(it.latitude, it.longitude) }
                routePolyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = Color.parseColor("#E41B1B")
                    outlinePaint.strokeWidth = 9f
                }
                binding.mapView.overlays.add(routePolyline)

                state.routeWaypoints.forEachIndexed { index, wp ->
                    val marker = Marker(binding.mapView).apply {
                        position = GeoPoint(wp.latitude, wp.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Waypoint #${index + 1}"
                        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_route)
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
}
