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

    @Test
    fun testRouteSimulator_crossCountryLongDistance() {
        // London to Paris (~340 km)
        val waypoints = listOf(
            RoutePoint(51.5074, -0.1278),
            RoutePoint(48.8566, 2.3522)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 800.0f, isLooping = false, transportMode = TransportMode.AIRCRAFT)
        assertTrue(sim.hasValidRoute())
        assertTrue(sim.totalDistanceMeters > 300_000.0) // > 300 km

        var count = 0
        var loc = sim.tick(5.0)
        while (loc != null && !loc.isCompleted && count < 1000) {
            loc = sim.tick(5.0)
            count++
        }
        assertNotNull(loc)
        assertTrue(loc!!.isCompleted)
        assertEquals(48.8566, loc.latitude, 0.05)
        assertEquals(2.3522, loc.longitude, 0.05)
    }

    @Test
    fun testRouteSimulator_corneringSpeedDecelerationAtTurn() {
        // Long runway North (~1100m), then 90° turn East (~1100m)
        val waypoints = listOf(
            RoutePoint(0.0, 0.0),
            RoutePoint(0.01, 0.0),      // Vertex: 90° turn from bearing 0° to 90°
            RoutePoint(0.01, 0.01)
        )

        val targetSpeedKmh = 120.0f
        val cruiseSpeedMps = targetSpeedKmh * 1000f / 3600f // 33.33 m/s
        val sim = RouteSimulator(waypoints, targetSpeedKmh = targetSpeedKmh, isLooping = false, transportMode = TransportMode.VEHICLE)

        var vertexReached = false
        var minSpeedNearVertex = Float.MAX_VALUE
        var reachedCruiseSpeedBeforeTurn = false

        val dt = 0.2
        var prevSpeed = 0.0f

        for (step in 0 until 2000) {
            val loc = sim.tick(dt) ?: break
            if (loc.isCompleted) break

            // Per-tick deceleration must never exceed maxDeceleration * dt
            if (loc.speedMps < prevSpeed) {
                val decel = (prevSpeed - loc.speedMps) / dt
                assertTrue("Deceleration $decel exceeded max 3.5 m/s² cap", decel <= 3.55)
            }
            prevSpeed = loc.speedMps

            // Check that cruise speed was reached before the corner
            if (loc.latitude < 0.007 && loc.speedMps >= cruiseSpeedMps * 0.98f) {
                reachedCruiseSpeedBeforeTurn = true
            }

            // In the vicinity of the vertex (approaching vertex latitude 0.01)
            if (loc.latitude in 0.008..0.010 && loc.longitude < 0.001) {
                if (loc.speedMps < minSpeedNearVertex) {
                    minSpeedNearVertex = loc.speedMps
                }
            }

            if (loc.longitude > 0.0001) {
                vertexReached = true
                break
            }
        }

        assertTrue("Vehicle must reach cruise speed before turn", reachedCruiseSpeedBeforeTurn)
        assertTrue("Vehicle must reach the vertex", vertexReached)

        // Acceptance criteria: peak-in-turn speed must be meaningfully lower than 90% of cruise speed (120 km/h)
        val minSpeedKmh = minSpeedNearVertex * 3.6f
        assertTrue(
            "Expected in-turn speed dip noticeably below 90% cruise (108 km/h), got $minSpeedKmh km/h",
            minSpeedKmh < (targetSpeedKmh * 0.90f)
        )
    }

    @Test
    fun testRouteSimulator_turnExitZoneHold() {
        val waypoints = listOf(
            RoutePoint(0.0, 0.0),
            RoutePoint(0.01, 0.0),      // Vertex: 90° turn
            RoutePoint(0.01, 0.01)
        )

        val targetSpeedKmh = 120.0f
        val cruiseSpeedMps = targetSpeedKmh * 1000f / 3600f
        val sim = RouteSimulator(waypoints, targetSpeedKmh = targetSpeedKmh, isLooping = false, transportMode = TransportMode.VEHICLE)

        val dt = 0.1
        var enteredSecondSegment = false
        var speedImmediatelyAfterVertex = 0.0f
        var checkedExitZone = false

        for (step in 0 until 3000) {
            val loc = sim.tick(dt) ?: break
            if (loc.isCompleted) break

            // Vertex is at (0.01, 0.0). Once longitude starts increasing, we're in the second segment.
            if (loc.longitude > 0.00005 && !enteredSecondSegment) {
                enteredSecondSegment = true
                speedImmediatelyAfterVertex = loc.speedMps
            }

            if (enteredSecondSegment && loc.longitude in 0.0001..0.0002) {
                // Assert no "instant re-acceleration" tick-to-tick jump back to cruise speed immediately after crossing
                assertTrue(
                    "Speed right after turn vertex ($speedImmediatelyAfterVertex m/s) should not instantly jump back to cruise speed ($cruiseSpeedMps m/s)",
                    loc.speedMps < cruiseSpeedMps * 0.92f
                )
                checkedExitZone = true
                break
            }
        }

        assertTrue("Should have entered second segment", enteredSecondSegment)
        assertTrue("Should have checked exit zone speed", checkedExitZone)
    }

    @Test
    fun testRouteSimulator_bearingInterpolationAcrossVertex() {
        val waypoints = listOf(
            RoutePoint(0.0, 0.0),
            RoutePoint(0.01, 0.0),      // Vertex from 0° (North) to 90° (East)
            RoutePoint(0.01, 0.01)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 60.0f, isLooping = false, transportMode = TransportMode.VEHICLE)
        val dt = 0.05 // High resolution ticks across turn

        var intermediateBearingsFound = 0
        var prevBearing = 0.0f

        for (step in 0 until 4000) {
            val loc = sim.tick(dt) ?: break
            if (loc.isCompleted) break

            val currentBearing = loc.bearingDegrees
            if (currentBearing in 10.0f..80.0f) {
                intermediateBearingsFound++
            }

            var deltaAngle = kotlin.math.abs(currentBearing - prevBearing)
            if (deltaAngle > 180f) deltaAngle = 360f - deltaAngle
            // In high resolution dt=0.05s, bearing shouldn't snap 90° in a single tick
            assertTrue(
                "Bearing jumped $deltaAngle degrees in a single 0.05s tick!",
                deltaAngle < 45.0f
            )

            prevBearing = currentBearing
        }

        assertTrue(
            "Bearing should interpolate smoothly through intermediate angles (10°..80°) across vertex",
            intermediateBearingsFound >= 5
        )
    }

    @Test
    fun testRouteSimulator_loopingStabilityManyLaps() {
        // Square route with 4 sharp 90-degree corners
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7769, -122.4194),
            RoutePoint(37.7769, -122.4174),
            RoutePoint(37.7749, -122.4174)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 100.0f, isLooping = true, transportMode = TransportMode.VEHICLE)

        for (step in 0 until 2000) {
            val loc = sim.tick(0.5)
            assertNotNull("Simulation tick should never be null while looping", loc)
            assertFalse("Looping simulation should never report completed", loc!!.isCompleted)
            assertFalse("Latitude must be finite number", loc.latitude.isNaN() || loc.latitude.isInfinite())
            assertFalse("Longitude must be finite number", loc.longitude.isNaN() || loc.longitude.isInfinite())
            assertFalse("Speed must be finite number", loc.speedMps.isNaN() || loc.speedMps.isInfinite())
            assertFalse("Bearing must be finite number", loc.bearingDegrees.isNaN() || loc.bearingDegrees.isInfinite())
            assertTrue("Bearing must be within [0, 360]", loc.bearingDegrees in 0.0f..360.0f)
            assertTrue("Speed must be non-negative", loc.speedMps >= 0.0f)
        }
    }

    @Test
    fun testRouteSimulator_waypointDwellStopTime() {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7769, -122.4194, stopDurationSeconds = 10), // 10 second stop at middle waypoint
            RoutePoint(37.7789, -122.4194)
        )

        val sim = RouteSimulator(waypoints, targetSpeedKmh = 50.0f, isLooping = false, transportMode = TransportMode.VEHICLE)

        var dwellDetected = false
        var dwellTickCount = 0

        for (step in 0 until 500) {
            val loc = sim.tick(1.0) ?: break
            if (loc.isCompleted) break

            // If near the second waypoint coordinates and speed is 0
            if (kotlin.math.abs(loc.latitude - 37.7769) < 0.0001 && loc.speedMps == 0.0f) {
                dwellDetected = true
                dwellTickCount++
            }
        }

        assertTrue("Dwell stop at Waypoint #2 should be detected", dwellDetected)
        // With 1.0s ticks and a 10s stop duration, dwell count should be around 10
        assertTrue("Dwell should last approximately 10 ticks (was $dwellTickCount)", dwellTickCount in 8..12)
    }
}
