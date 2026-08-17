package com.fakegps.mocklocation.service

import com.fakegps.mocklocation.engine.MockLocationError
import com.fakegps.mocklocation.simulator.SimulationMode

sealed class ServiceState {
    object Idle : ServiceState()

    data class Running(
        val mode: SimulationMode,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speedMps: Float,
        val bearingDegrees: Float,
        val isPaused: Boolean = false,
        val totalDistanceMeters: Double = 0.0,
        val distanceCoveredMeters: Double = 0.0,
        val distanceRemainingMeters: Double = 0.0
    ) : ServiceState()

    data class Error(
        val error: MockLocationError
    ) : ServiceState()
}
