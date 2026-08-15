package com.fakegps.mocklocation

import com.fakegps.mocklocation.engine.GeoUtils
import org.junit.Assert.*
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun testHaversineDistance_samePoint_isZero() {
        val distance = GeoUtils.calculateDistanceMeters(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun testHaversineDistance_sanFranciscoToOakland() {
        // SF to Oakland ~ 13 km
        val distance = GeoUtils.calculateDistanceMeters(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(distance in 12000.0..15000.0)
    }

    @Test
    fun testCalculateBearing_dueNorth() {
        val bearing = GeoUtils.calculateBearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0f, bearing, 0.1f)
    }

    @Test
    fun testCalculateBearing_dueEast() {
        val bearing = GeoUtils.calculateBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0f, bearing, 0.1f)
    }

    @Test
    fun testComputeDestinationPoint_north() {
        val startLat = 37.7749
        val startLon = -122.4194
        val (destLat, destLon) = GeoUtils.computeDestinationPoint(startLat, startLon, 0.0f, 1000.0)

        assertTrue(destLat > startLat)
        assertEquals(startLon, destLon, 0.0001)

        val verifiedDist = GeoUtils.calculateDistanceMeters(startLat, startLon, destLat, destLon)
        assertEquals(1000.0, verifiedDist, 1.0)
    }

    @Test
    fun testInterpolate() {
        val (midLat, midLon) = GeoUtils.interpolate(10.0, 20.0, 20.0, 40.0, 0.5)
        assertEquals(15.0, midLat, 0.0001)
        assertEquals(30.0, midLon, 0.0001)
    }
}
