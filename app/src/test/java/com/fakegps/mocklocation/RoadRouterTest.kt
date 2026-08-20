package com.fakegps.mocklocation

import com.fakegps.mocklocation.simulator.RoadRouter
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.TransportMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoadRouterTest {

    @Test
    fun testRoadRouter_directGeodesicFallback() = runBlocking {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194),
            RoutePoint(37.7849, -122.4094)
        )

        // Generate direct geodesic path
        val path = RoadRouter.generateShortestRoute(waypoints, TransportMode.VEHICLE)
        assertTrue("Direct path should produce interpolated points", path.size > 2)
        assertEquals(37.7749, path.first().latitude, 0.0001)
        assertEquals(37.7849, path.last().latitude, 0.0001)
    }

    @Test
    fun testRoadRouter_aircraftFlightCorridor() = runBlocking {
        val waypoints = listOf(
            RoutePoint(37.7749, -122.4194, altitude = 10.0),
            RoutePoint(40.7128, -74.0060, altitude = 15.0)
        )

        val result = RoadRouter.resolveRealWorldRouteWithStatus(waypoints, TransportMode.AIRCRAFT)
        assertFalse(result.isFallbackDirectPath)
        assertTrue("Flight corridor should generate points", result.waypoints.size > 10)
        // Check cruise altitude of 9500m
        val cruisePoint = result.waypoints[result.waypoints.size / 2]
        assertEquals(9500.0, cruisePoint.altitude, 1.0)
    }

    @Test
    fun testRoadRouter_shipMarineRoute() = runBlocking {
        val waypoints = listOf(
            RoutePoint(37.8000, -122.4200),
            RoutePoint(37.8100, -122.4100)
        )

        val result = RoadRouter.resolveRealWorldRouteWithStatus(waypoints, TransportMode.SHIP)
        assertFalse(result.isFallbackDirectPath)
        assertTrue("Ship route should generate points", result.waypoints.size > 2)
        for (pt in result.waypoints) {
            assertEquals("Ship waypoints must be locked to sea level (0.0m)", 0.0, pt.altitude, 0.001)
        }
    }
}
