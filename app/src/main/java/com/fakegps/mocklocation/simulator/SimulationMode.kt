package com.fakegps.mocklocation.simulator

sealed class SimulationMode {
    object Idle : SimulationMode()

    data class Fixed(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double = 15.0,
        val enableJitter: Boolean = true
    ) : SimulationMode()

    data class Route(
        val waypoints: List<RoutePoint>,
        val speedKmh: Float = 20.0f,
        val isLooping: Boolean = true
    ) : SimulationMode()

    data class Joystick(
        var currentLat: Double,
        var currentLon: Double,
        var speedKmh: Float = 10.0f,
        var angleDegrees: Float = 0.0f,
        var magnitude: Float = 0.0f
    ) : SimulationMode()
}
