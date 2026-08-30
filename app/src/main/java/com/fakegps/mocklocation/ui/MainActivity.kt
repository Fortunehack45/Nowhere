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
        setupIpShield()
        setupGhostCloak()
        requestInitialPermissions()
        observeUiState()

        binding.btnHeaderWidgets.setOnClickListener {
            WidgetGalleryBottomSheet().show(supportFragmentManager, "WIDGET_GALLERY")
        }

        binding.btnHeaderSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(2000L)
            val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.checkForUpdates(this@MainActivity, forceCheck = false)
            if (updateInfo.isUpdateAvailable && !isFinishing && !isDestroyed) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val bottomSheet = com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.newInstance(updateInfo)
                    bottomSheet.show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.TAG)
                }
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
            IpChangerBottomSheet().show(supportFragmentManager, "IP_CHANGER_DIALOG")
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

        if (intent.getBooleanExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_OPEN_UPDATE_DIALOG, false)) {
            val latestVersion = intent.getStringExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_UPDATE_VERSION) ?: ""
            val releaseTitle = intent.getStringExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_UPDATE_TITLE) ?: "Nowhere Update"
            val releaseNotes = intent.getStringExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_UPDATE_NOTES) ?: ""
            val downloadUrl = intent.getStringExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_UPDATE_DOWNLOAD_URL) ?: ""
            val htmlUrl = intent.getStringExtra(com.fakegps.mocklocation.util.AppUpdateManager.EXTRA_UPDATE_HTML_URL) ?: ""

            val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.UpdateInfo(
                isUpdateAvailable = true,
                currentVersion = com.fakegps.mocklocation.BuildConfig.VERSION_NAME,
                latestVersion = latestVersion.ifEmpty { com.fakegps.mocklocation.BuildConfig.VERSION_NAME },
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                htmlUrl = htmlUrl
            )
            com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.newInstance(updateInfo)
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.TAG)
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
        binding.mapView.onResume()
        binding.mapView.setTileSource(settingsPrefs.getOsmTileSource())
        viewModel.refreshPermissionStates()
        checkBatteryOptimizationOnFirstLaunch()
        renderGhostCloakBadge()
        viewModel.requestWeatherUpdate(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude, forceRefresh = true)
        if (com.fakegps.mocklocation.data.preferences.SessionPreferences(this).hasValidActiveSession()) {
            com.fakegps.mocklocation.service.SessionTimerManager.resumeExistingTimer(this)
        }
        if (binding.adBannerContainer.childCount == 0) {
            com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = true)
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

    private fun checkBatteryOptimizationOnFirstLaunch() {
        val prefs = SessionPreferences(this)
        if (!settingsPrefs.hasCompletedFeatureWalkthrough) {
            settingsPrefs.hasCompletedFeatureWalkthrough = true
            com.fakegps.mocklocation.ui.dialogs.AppTutorialDialog(this) {
                if (!prefs.hasPromptedBatteryOptimization && !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
                    prefs.hasPromptedBatteryOptimization = true
                    com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog(this).show()
                }
            }.show()
        } else if (!prefs.hasPromptedBatteryOptimization && !PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            prefs.hasPromptedBatteryOptimization = true
            com.fakegps.mocklocation.ui.dialogs.BatteryOptimizationDialog(this).show()
        } else if (!prefs.hasPromptedExactAlarmPermission && !PermissionHelper.canScheduleExactAlarms(this)) {
            prefs.hasPromptedExactAlarmPermission = true
            com.fakegps.mocklocation.ui.dialogs.ExactAlarmPermissionDialog(this).show()
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

        updateFixedPinMarker(viewModel.uiState.value.fixedLatitude, viewModel.uiState.value.fixedLongitude)
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
                val speedKmh = viewModel.uiState.value.joystickSpeedKmh
                if (!viewModel.uiState.value.isServiceRunning && magnitude > 0.02f) {
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

        // Floating Tools Hide/Unhide Toggle
        val isExpanded = settingsPrefs.isSideMenuExpanded
        binding.layoutSideButtons.visibility = if (isExpanded) View.VISIBLE else View.GONE
        binding.dividerSideMenuToggle.visibility = if (isExpanded) View.VISIBLE else View.GONE
        binding.btnToggleSideMenu.setImageResource(if (isExpanded) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
        binding.btnToggleSideMenu.contentDescription = if (isExpanded) "Collapse Floating Tools" else "Expand Floating Tools"

        binding.btnToggleSideMenu.setOnClickListener {
            val currentlyExpanded = binding.layoutSideButtons.visibility == View.VISIBLE
            val willBeExpanded = !currentlyExpanded
            settingsPrefs.isSideMenuExpanded = willBeExpanded

            binding.layoutSideButtons.visibility = if (willBeExpanded) View.VISIBLE else View.GONE
            binding.dividerSideMenuToggle.visibility = if (willBeExpanded) View.VISIBLE else View.GONE
            binding.btnToggleSideMenu.setImageResource(if (willBeExpanded) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
            binding.btnToggleSideMenu.contentDescription = if (willBeExpanded) "Collapse Floating Tools" else "Expand Floating Tools"
            
            val msg = if (willBeExpanded) "Floating tools expanded" else "Floating tools collapsed"
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

        binding.fabAppTutorial.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.AppTutorialDialog(this).show()
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
            com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, bestNode.id)
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
    }

    private fun startRouteSpoofing() {
        if (!verifyMockAppSelected()) return
        if (!ensureActiveSessionOrPrompt { startRouteSpoofing() }) return
        performHapticFeedbackIfEnabled()

        val state = viewModel.uiState.value
        val effectiveWaypoints = if (state.routeWaypoints.size >= 2) state.routeWaypoints else state.userKeypoints
        if (effectiveWaypoints.size < 2) {
            Toast.makeText(this, "Plot at least 2 waypoints by tapping the map or importing a GPX file.", Toast.LENGTH_LONG).show()
            return
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
    }

    private fun startJoystickSpoofing() {
        if (!verifyMockAppSelected()) return
        if (!ensureActiveSessionOrPrompt { startJoystickSpoofing() }) return
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
                        binding.layoutIpShieldBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_active_bg)
                        binding.ivShieldIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.badge_active_text)
                        binding.tvIpShieldBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.badge_active_text))
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
            binding.tvGhostCloakBadge.text = "CLOAK ON"
        } else {
            binding.layoutGhostCloakBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_elevated)
            binding.ivGhostCloakIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
            binding.tvGhostCloakBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvGhostCloakBadge.text = "RAW GPS"
        }
    }

    private fun observeUiState() {
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
            com.fakegps.mocklocation.service.SessionTimerManager.timerState.collectLatest { timerState ->
                if (!isFinishing && !isDestroyed) {
                    if (timerState.isRunning) {
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
            binding.btnFixedToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_stop_bg)
            binding.btnFixedToggle.setTextColor(ContextCompat.getColor(this, R.color.btn_stop_text))
            binding.btnFixedToggle.iconTint = ContextCompat.getColorStateList(this, R.color.btn_stop_text)
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
        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.ROUTE) {
            binding.btnRouteToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnRouteToggle.setIconResource(R.drawable.ic_stop)
            binding.btnRouteToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_stop_bg)
            binding.btnRouteToggle.setTextColor(ContextCompat.getColor(this, R.color.btn_stop_text))
            binding.btnRouteToggle.iconTint = ContextCompat.getColorStateList(this, R.color.btn_stop_text)
            binding.btnRoutePause.visibility = View.VISIBLE

            val isPaused = runningState?.isPaused == true
            binding.btnRoutePause.text = if (isPaused) getString(R.string.btn_resume_route) else getString(R.string.btn_pause_route)
            binding.btnRoutePause.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
            binding.btnRoutePause.iconTint = ContextCompat.getColorStateList(this, if (isPaused) R.color.badge_success_text else R.color.primary_bright)
            binding.btnRoutePause.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            if (runningState != null && runningState.totalDistanceMeters > 0) {
                binding.layoutRouteTelemetry.visibility = View.VISIBLE
                val covered = settingsPrefs.formatDistance(runningState.distanceCoveredMeters)
                val total = settingsPrefs.formatDistance(runningState.totalDistanceMeters)
                val remaining = settingsPrefs.formatDistance(runningState.distanceRemainingMeters)
                val progress = ((runningState.distanceCoveredMeters / runningState.totalDistanceMeters) * 100).toInt().coerceIn(0, 100)

                val speedMps = runningState.speedMps
                val remainingMeters = runningState.distanceRemainingMeters
                val etaText = if (speedMps > 0.3f && remainingMeters > 5.0) {
                    val secondsLeft = (remainingMeters / speedMps).toLong()
                    val hours = secondsLeft / 3600
                    val minutes = (secondsLeft % 3600) / 60
                    val seconds = secondsLeft % 60
                    if (hours > 0) {
                        String.format("⏱️ ETA: %dh %02dm", hours, minutes)
                    } else {
                        String.format("⏱️ ETA: %02dm %02ds", minutes, seconds)
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
            } else {
                binding.layoutRouteTelemetry.visibility = View.GONE
            }
        } else {
            binding.layoutRouteTelemetry.visibility = View.GONE
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
        binding.joystickOverlay.visibility = if (state.selectedTab == SelectedModeTab.JOYSTICK) View.VISIBLE else View.GONE

        if (state.isServiceRunning && state.selectedTab == SelectedModeTab.JOYSTICK) {
            binding.btnJoystickToggle.text = getString(R.string.btn_stop_simulation)
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_stop)
            binding.btnJoystickToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_stop_bg)
            binding.btnJoystickToggle.setTextColor(ContextCompat.getColor(this, R.color.btn_stop_text))
            binding.btnJoystickToggle.iconTint = ContextCompat.getColorStateList(this, R.color.btn_stop_text)

            val running = state.serviceState as? ServiceState.Running
            if (running != null && running.mode is SimulationMode.Joystick) {
                updateFixedPinMarker(running.latitude, running.longitude)
                binding.mapView.controller.setCenter(GeoPoint(running.latitude, running.longitude))
            }
        } else {
            binding.btnJoystickToggle.text = "Engage Joystick"
            binding.btnJoystickToggle.setIconResource(R.drawable.ic_play)
            binding.btnJoystickToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
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
                        outlinePaint.color = Color.parseColor("#E41B1B")
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
}
