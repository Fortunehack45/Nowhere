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
        val updated = _uiState.value.routeWaypoints.toMutableList().apply {
            add(RoutePoint(latitude, longitude))
        }
        sessionPrefs.saveWaypoints(updated)
        _uiState.update { it.copy(routeWaypoints = updated) }
    }

    fun clearRouteWaypoints() {
        sessionPrefs.saveWaypoints(emptyList())
        _uiState.update { it.copy(routeWaypoints = emptyList()) }
    }

    fun reverseRouteWaypoints() {
        val reversed = _uiState.value.routeWaypoints.reversed()
        sessionPrefs.saveWaypoints(reversed)
        _uiState.update { it.copy(routeWaypoints = reversed, statusMessage = "Route reversed.") }
    }

    fun setRouteSpeed(speedKmh: Float) {
        sessionPrefs.lastSpeedKmh = speedKmh
        _uiState.update { it.copy(routeSpeedKmh = speedKmh) }
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

    fun searchAddress(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        _uiState.update { it.copy(isSearching = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(getApplication(), Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocationName(query, 6) { addresses ->
                        val mapped = addresses.mapNotNull { it.toSearchResult() }
                        _uiState.update { it.copy(searchResults = mapped, isSearching = false) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 6) ?: emptyList()
                    val mapped = addresses.mapNotNull { it.toSearchResult() }
                    _uiState.update { it.copy(searchResults = mapped, isSearching = false) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        searchResults = emptyList(),
                        isSearching = false
                    )
                }
            }
        }
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
                is ServiceState.Running -> current.copy(
                    isServiceRunning = true,
                    serviceState = state,
                    activeError = null
                )
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
