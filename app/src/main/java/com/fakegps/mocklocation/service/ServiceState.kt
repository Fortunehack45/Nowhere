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
        val isPaused: Boolean = false
    ) : ServiceState()

    data class Error(
        val error: MockLocationError
    ) : ServiceState()
}
