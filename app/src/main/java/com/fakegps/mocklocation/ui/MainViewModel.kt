package com.fakegps.mocklocation.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.data.db.SavedRoute
import com.fakegps.mocklocation.data.db.SearchHistoryItem
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.engine.MockLocationError
import com.fakegps.mocklocation.service.ServiceState
import com.fakegps.mocklocation.simulator.GpxParser
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val favoriteDao = db.favoriteDao()
    private val searchHistoryDao = db.searchHistoryDao()
    private val savedRouteDao = db.savedRouteDao()
    private val sessionPrefs = SessionPreferences(application)

    private val _uiState = MutableStateFlow(
        MainUiState(
            fixedLatitude = sessionPrefs.lastLatitude,
            fixedLongitude = sessionPrefs.lastLongitude,
            routeSpeedKmh = sessionPrefs.lastSpeedKmh,
            isRouteLooping = sessionPrefs.isLooping,
            routeWaypoints = sessionPrefs.getWaypoints()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val allFavorites: Flow<List<FavoriteLocation>> = favoriteDao.getAllFavoritesFlow()
    val allTags: Flow<List<String>> = favoriteDao.getAllTagsFlow()
    val recentSearches: Flow<List<SearchHistoryItem>> = searchHistoryDao.getRecentSearchesFlow()
    val allSavedRoutes: Flow<List<SavedRoute>> = savedRouteDao.getAllSavedRoutesFlow()

    init {
        refreshPermissionStates()
    }

    fun refreshPermissionStates() {
        val app = getApplication<Application>()
        val mockEnabled = PermissionHelper.isMockLocationEnabled(app)
        val fineLocation = PermissionHelper.hasFineLocationPermission(app)
        val notifications = PermissionHelper.hasNotificationPermission(app)
        val batteryOptimized = PermissionHelper.isIgnoringBatteryOptimizations(app)

        _uiState.update { current ->
            current.copy(
                isMockAppEnabled = mockEnabled,
                hasFineLocationPermission = fineLocation,
                hasNotificationPermission = notifications,
                isIgnoringBatteryOptimizations = batteryOptimized
            )
        }
    }

    fun setSelectedTab(tab: SelectedModeTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setFixedCoordinates(latitude: Double, longitude: Double) {
        sessionPrefs.lastLatitude = latitude
        sessionPrefs.lastLongitude = longitude
        _uiState.update { it.copy(fixedLatitude = latitude, fixedLongitude = longitude) }
    }

    fun addRouteWaypoint(latitude: Double, longitude: Double) {
        val newPoint = RoutePoint(latitude, longitude)
        val currentKeys = _uiState.value.userKeypoints.toMutableList().apply { add(newPoint) }
        _uiState.update { it.copy(userKeypoints = currentKeys) }

        if (currentKeys.size == 1) {
            _uiState.update { it.copy(routeWaypoints = currentKeys) }
            sessionPrefs.saveWaypoints(currentKeys)
        } else if (currentKeys.size >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolved = com.fakegps.mocklocation.simulator.RoadRouter.resolveRealWorldRoute(currentKeys, _uiState.value.transportMode)
                sessionPrefs.saveWaypoints(resolved)
                _uiState.update { it.copy(routeWaypoints = resolved) }
            }
        }
    }

    fun clearRouteWaypoints() {
        sessionPrefs.saveWaypoints(emptyList())
        _uiState.update { it.copy(routeWaypoints = emptyList(), userKeypoints = emptyList()) }
    }

    fun reverseRouteWaypoints() {
        val reversedKeys = _uiState.value.userKeypoints.reversed()
        _uiState.update { it.copy(userKeypoints = reversedKeys) }
        if (reversedKeys.size >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolved = com.fakegps.mocklocation.simulator.RoadRouter.resolveRealWorldRoute(reversedKeys, _uiState.value.transportMode)
                sessionPrefs.saveWaypoints(resolved)
                _uiState.update { it.copy(routeWaypoints = resolved, statusMessage = "Route reversed.") }
            }
        } else {
            val reversedAll = _uiState.value.routeWaypoints.reversed()
            sessionPrefs.saveWaypoints(reversedAll)
            _uiState.update { it.copy(routeWaypoints = reversedAll, statusMessage = "Route reversed.") }
        }
    }

    fun setRouteSpeed(speedKmh: Float) {
        sessionPrefs.lastSpeedKmh = speedKmh
        _uiState.update { it.copy(routeSpeedKmh = speedKmh) }
    }

    fun setTransportMode(mode: com.fakegps.mocklocation.simulator.TransportMode) {
        sessionPrefs.lastSpeedKmh = mode.defaultSpeedKmh
        _uiState.update {
            it.copy(
                transportMode = mode,
                routeSpeedKmh = mode.defaultSpeedKmh,
                statusMessage = "Transport mode: ${mode.title}"
            )
        }

        val keys = if (_uiState.value.userKeypoints.size >= 2) _uiState.value.userKeypoints else _uiState.value.routeWaypoints
        if (keys.size >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolved = com.fakegps.mocklocation.simulator.RoadRouter.resolveRealWorldRoute(keys, mode)
                sessionPrefs.saveWaypoints(resolved)
                _uiState.update { it.copy(routeWaypoints = resolved) }
            }
        }
    }

    fun setRouteLooping(isLooping: Boolean) {
        sessionPrefs.isLooping = isLooping
        _uiState.update { it.copy(isRouteLooping = isLooping) }
    }

    fun setJoystickSpeed(speedKmh: Float) {
        _uiState.update { it.copy(joystickSpeedKmh = speedKmh) }
    }

    fun importGpxRoute(inputStream: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parsed = GpxParser.parse(inputStream)
                if (parsed.isNotEmpty()) {
                    sessionPrefs.saveWaypoints(parsed)
                    _uiState.update {
                        it.copy(
                            routeWaypoints = parsed,
                            selectedTab = SelectedModeTab.ROUTE,
                            statusMessage = "Loaded ${parsed.size} waypoints from GPX file."
                        )
                    }
                } else {
                    _uiState.update { it.copy(statusMessage = "No waypoints found in GPX file.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Failed to parse GPX: ${e.localizedMessage}") }
            }
        }
    }

    // --- Search & History ---

    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchAddress(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        // Direct Coordinate match check: "37.7749, -122.4194" or "37.7749 -122.4194"
        val coordMatch = parseCoordinates(trimmed)
        if (coordMatch != null) {
            val (lat, lon) = coordMatch
            val result = AddressSearchResult(
                title = String.format(Locale.US, "Coordinates: %.5f, %.5f", lat, lon),
                snippet = "Direct Lat/Lon Navigation Target",
                latitude = lat,
                longitude = lon
            )
            _uiState.update { it.copy(searchResults = listOf(result), isSearching = false) }
            return
        }

        if (trimmed.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250) // Debounce rapid keystrokes

            val results = performGeocodingWithFallback(trimmed)
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        }
    }

    fun clearSearchResults() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
    }

    private suspend fun performGeocodingWithFallback(query: String): List<AddressSearchResult> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AddressSearchResult>()

        // 1. Try Android Native Geocoder
        try {
            val geocoder = Geocoder(getApplication(), Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val deferred = kotlinx.coroutines.CompletableDeferred<List<AddressSearchResult>>()
                geocoder.getFromLocationName(query, 6) { addresses ->
                    val mapped = addresses.mapNotNull { it.toSearchResult() }
                    deferred.complete(mapped)
                }
                val res = kotlinx.coroutines.withTimeoutOrNull(2500) { deferred.await() }
                if (!res.isNullOrEmpty()) {
                    list.addAll(res)
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 6) ?: emptyList()
                val mapped = addresses.mapNotNull { it.toSearchResult() }
                if (mapped.isNotEmpty()) {
                    list.addAll(mapped)
                }
            }
        } catch (ignored: Exception) {}

        if (list.isNotEmpty()) return@withContext list

        // 2. High-reliability OpenStreetMap Nominatim Search Fallback
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val urlString = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=8&q=$encoded"
            val connection = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("User-Agent", "NowhereLocationSimulator/1.0 (Android Search)")
            }

            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    val displayName = obj.getString("display_name")
                    val title = displayName.split(",").firstOrNull()?.trim() ?: displayName
                    val snippet = displayName.split(",").drop(1).joinToString(",").trim().ifBlank { displayName }

                    list.add(
                        AddressSearchResult(
                            title = title,
                            snippet = snippet,
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }
            }
        } catch (ignored: Exception) {}

        return@withContext list
    }

    private fun parseCoordinates(input: String): Pair<Double, Double>? {
        val clean = input.replace(",", " ").replace(";", " ").trim()
        val parts = clean.split("\\s+".toRegex())
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                return Pair(lat, lon)
            }
        }
        return null
    }

    fun recordSearchHistory(query: String, title: String, snippet: String, latitude: Double, longitude: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryDao.insertSearch(
                SearchHistoryItem(
                    query = query,
                    title = title,
                    snippet = snippet,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
    }

    fun deleteSearchHistoryItem(item: SearchHistoryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryDao.deleteSearch(item)
        }
    }

    fun clearAllSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryDao.clearHistory()
        }
    }

    private fun Address.toSearchResult(): AddressSearchResult? {
        val title = featureName ?: getAddressLine(0) ?: return null
        val snippet = getAddressLine(0) ?: "$latitude, $longitude"
        return AddressSearchResult(
            title = title,
            snippet = snippet,
            latitude = latitude,
            longitude = longitude
        )
    }

    // --- Saved Routes Management ---

    fun saveCurrentRoute(name: String) {
        val waypoints = _uiState.value.routeWaypoints
        if (waypoints.size < 2) return

        viewModelScope.launch(Dispatchers.IO) {
            var totalDist = 0.0
            for (i in 0 until waypoints.size - 1) {
                totalDist += GeoUtils.calculateDistanceMeters(
                    waypoints[i].latitude, waypoints[i].longitude,
                    waypoints[i + 1].latitude, waypoints[i + 1].longitude
                )
            }

            val jsonArray = JSONArray()
            for (wp in waypoints) {
                val obj = JSONObject().apply {
                    put("lat", wp.latitude)
                    put("lon", wp.longitude)
                    put("alt", wp.altitude)
                }
                jsonArray.put(obj)
            }

            savedRouteDao.insertRoute(
                SavedRoute(
                    name = name.ifBlank { "Route (${waypoints.size} pts)" },
                    waypointsJson = jsonArray.toString(),
                    waypointsCount = waypoints.size,
                    totalDistanceMeters = totalDist,
                    defaultSpeedKmh = _uiState.value.routeSpeedKmh,
                    isLooping = _uiState.value.isRouteLooping
                )
            )
            _uiState.update { it.copy(statusMessage = "Route '$name' saved successfully.") }
        }
    }

    fun loadSavedRoute(route: SavedRoute) {
        val points = mutableListOf<RoutePoint>()
        try {
            val arr = JSONArray(route.waypointsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                points.add(
                    RoutePoint(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        altitude = obj.optDouble("alt", 0.0)
                    )
                )
            }
            if (points.isNotEmpty()) {
                sessionPrefs.saveWaypoints(points)
                _uiState.update {
                    it.copy(
                        routeWaypoints = points,
                        routeSpeedKmh = route.defaultSpeedKmh,
                        isRouteLooping = route.isLooping,
                        selectedTab = SelectedModeTab.ROUTE,
                        statusMessage = "Loaded saved route: ${route.name}"
                    )
                }
            }
        } catch (ignored: Exception) {
        }
    }

    fun deleteSavedRoute(route: SavedRoute) {
        viewModelScope.launch(Dispatchers.IO) {
            savedRouteDao.deleteRoute(route)
        }
    }

    // --- Service Lifecycle & Errors ---

    fun onServiceStateUpdated(state: ServiceState) {
        _uiState.update { current ->
            when (state) {
                is ServiceState.Idle -> current.copy(
                    isServiceRunning = false,
                    serviceState = state,
                    activeError = null
                )
                is ServiceState.Running -> {
                    val isModeFixed = state.mode is com.fakegps.mocklocation.simulator.SimulationMode.Fixed
                    val updatedLat = if (isModeFixed) state.latitude else current.fixedLatitude
                    val updatedLon = if (isModeFixed) state.longitude else current.fixedLongitude
                    val wasRouting = current.serviceState is ServiceState.Running && current.serviceState.mode is com.fakegps.mocklocation.simulator.SimulationMode.Route
                    val statusMsg = if (wasRouting && isModeFixed) "🚩 Route completed! Location locked at destination." else current.statusMessage

                    current.copy(
                        isServiceRunning = true,
                        serviceState = state,
                        activeError = null,
                        fixedLatitude = updatedLat,
                        fixedLongitude = updatedLon,
                        statusMessage = statusMsg
                    )
                }
                is ServiceState.Error -> current.copy(
                    isServiceRunning = false,
                    serviceState = state,
                    activeError = state.error
                )
            }
        }
    }

    fun clearActiveError() {
        _uiState.update { it.copy(activeError = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    // --- Favorites ---

    fun saveFavorite(name: String, latitude: Double, longitude: Double, tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            favoriteDao.insertFavorite(
                FavoriteLocation(
                    name = name.ifBlank { "Location (${String.format("%.4f, %.4f", latitude, longitude)})" },
                    latitude = latitude,
                    longitude = longitude,
                    tag = tag.ifBlank { "Default" }
                )
            )
        }
    }

    fun deleteFavorite(favorite: FavoriteLocation) {
        viewModelScope.launch(Dispatchers.IO) {
            favoriteDao.deleteFavorite(favorite)
        }
    }

    fun importFavoritesJson(jsonContent: String, onResult: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = com.fakegps.mocklocation.ui.favorites.JsonBackupHelper.importFromJson(jsonContent)
                if (list.isNotEmpty()) {
                    favoriteDao.insertAll(list)
                    withContext(Dispatchers.Main) { onResult(list.size) }
                } else {
                    withContext(Dispatchers.Main) { onResult(0) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(-1) }
            }
        }
    }
}
