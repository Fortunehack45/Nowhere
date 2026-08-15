package com.fakegps.mocklocation

import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.RouteSimulator
import com.fakegps.mocklocation.simulator.TransportMode
import org.junit.Assert.*
import org.junit.Test

class RouteSimulatorTest {

    @Test
    fun testRouteSimulator_invalidRoute() {
        val sim = RouteSimulator(emptyList())
        assertFalse(sim.hasValidRoute())
        assertNull(sim.tick(1.0))
    }

    @Test
    fun testRouteSimulator_progression() {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7759, -122.4194),
            RoutePoint(37.7769, -122.4194)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 36.0f, isLooping = false, transportMode = TransportMode.VEHICLE)
        assertTrue(sim.hasValidRoute())
        assertTrue(sim.totalDistanceMeters > 200.0)

        val loc1 = sim.tick(1.0)
        assertNotNull(loc1)
        assertTrue(loc1!!.speedMps > 0.0f)

        // Advance further
        var lastLoc = loc1
        for (i in 0 until 50) {
            val step = sim.tick(1.0)
            if (step != null) {
                lastLoc = step
            }
        }

        assertTrue(lastLoc!!.isCompleted)
    }

    @Test
    fun testRouteSimulator_transportModes() {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7849, -122.4194)
        )

        val flightSim = RouteSimulator(waypoints, targetSpeedKmh = 500.0f, transportMode = TransportMode.AIRCRAFT)
        val loc = flightSim.tick(1.0)
        assertNotNull(loc)
        assertEquals(9500.0, loc!!.altitude, 0.1)

        val shipSim = RouteSimulator(waypoints, targetSpeedKmh = 25.0f, transportMode = TransportMode.SHIP)
        val shipLoc = shipSim.tick(1.0)
        assertNotNull(shipLoc)
        assertEquals(0.0, shipLoc!!.altitude, 0.1)
    }

    @Test
    fun testRouteSimulator_looping() {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7759, -122.4194)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 72.0f, isLooping = true)
        for (i in 0 until 100) {
            val loc = sim.tick(1.0)
            assertNotNull(loc)
            assertFalse(loc!!.isCompleted)
        }
    }
}
