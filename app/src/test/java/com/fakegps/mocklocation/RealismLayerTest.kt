package com.fakegps.mocklocation

import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.engine.RealismLayer
import org.junit.Assert.*
import org.junit.Test

class RealismLayerTest {

    private val realismLayer = RealismLayer()

    @Test
    fun testApplyJitter_withinBounds() {
        val lat = 37.7749
        val lon = -122.4194

        for (i in 0 until 50) {
            val (jitterLat, jitterLon) = realismLayer.applyJitter(lat, lon)
            val distance = GeoUtils.calculateDistanceMeters(lat, lon, jitterLat, jitterLon)
            assertTrue("Distance was $distance", distance in 0.05..10.0)
        }
    }

    @Test
    fun testTruncateCoordinates() {
        val lat = 37.774912345
        val lon = -122.419456789

        // Test fallback full precision
        val (fullLat, fullLon) = realismLayer.truncateIfNeeded(lat, lon)
        assertEquals(lat, fullLat, 0.0000001)
        assertEquals(lon, fullLon, 0.0000001)
    }

    @Test
    fun testGenerateAccuracy_realisticRange() {
        for (i in 0 until 50) {
            val acc = realismLayer.generateHorizontalAccuracy()
            assertTrue(acc in 0.5f..10.0f)
        }
    }

    @Test
    fun testAdaptiveIntervals() {
        assertEquals(1000L, realismLayer.getAdaptiveIntervalMs(isMoving = true))
        assertEquals(5000L, realismLayer.getAdaptiveIntervalMs(isMoving = false))
    }
}
