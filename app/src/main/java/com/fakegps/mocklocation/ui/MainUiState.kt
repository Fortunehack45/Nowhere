package com.fakegps.mocklocation.ui

import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.engine.MockLocationError
import com.fakegps.mocklocation.service.ServiceState
import com.fakegps.mocklocation.simulator.RoutePoint

enum class SelectedModeTab {
    FIXED, ROUTE, JOYSTICK
}

data class MainUiState(
    val selectedTab: SelectedModeTab = SelectedModeTab.FIXED,
    val isMockAppEnabled: Boolean = false,
    val hasFineLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val isServiceRunning: Boolean = false,
    val serviceState: ServiceState = ServiceState.Idle,
    val fixedLatitude: Double = 37.7749,
    val fixedLongitude: Double = -122.4194,
    val userKeypoints: List<RoutePoint> = emptyList(),
    val routeWaypoints: List<RoutePoint> = emptyList(),
    val routeSpeedKmh: Float = 20.0f,
    val isRouteLooping: Boolean = true,
    val transportMode: com.fakegps.mocklocation.simulator.TransportMode = com.fakegps.mocklocation.simulator.TransportMode.VEHICLE,
    val joystickSpeedKmh: Float = 10.0f,
    val searchResults: List<AddressSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val canUndoRoute: Boolean = false,
    val canRedoRoute: Boolean = false,
    val isUsingDirectRouteFallback: Boolean = false,
    val activeError: MockLocationError? = null,
    val statusMessage: String? = null
)

data class AddressSearchResult(
    val title: String,
    val snippet: String,
    val latitude: Double,
    val longitude: Double
)
