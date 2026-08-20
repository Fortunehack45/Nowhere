package com.fakegps.mocklocation.simulator

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val targetSpeedMps: Float = 5.0f, // Meters per second
    val cumulativeDistanceMeters: Double = 0.0,
    val stopDurationSeconds: Int = 0 // Optional dwell/stop duration in seconds at this waypoint
)
