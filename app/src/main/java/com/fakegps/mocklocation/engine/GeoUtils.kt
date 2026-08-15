package com.fakegps.mocklocation.engine

import kotlin.math.*

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the great-circle distance between two coordinates in meters using the Haversine formula.
     */
    fun calculateDistanceMeters(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Double {
        val lat1Rad = Math.toRadians(startLat)
        val lat2Rad = Math.toRadians(endLat)
        val deltaLatRad = Math.toRadians(endLat - startLat)
        val deltaLonRad = Math.toRadians(endLon - startLon)

        val a = sin(deltaLatRad / 2.0).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates the initial bearing (forward azimuth) from start coordinate to end coordinate in degrees [0, 360).
     */
    fun calculateBearing(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Float {
        val lat1Rad = Math.toRadians(startLat)
        val lat2Rad = Math.toRadians(endLat)
        val deltaLonRad = Math.toRadians(endLon - startLon)

        val y = sin(deltaLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLonRad)

        val bearingRad = atan2(y, x)
        val bearingDeg = (Math.toDegrees(bearingRad) + 360.0) % 360.0

        return bearingDeg.toFloat()
    }

    /**
     * Calculates the destination coordinate given a starting coordinate, bearing in degrees, and distance in meters.
     */
    fun computeDestinationPoint(
        startLat: Double,
        startLon: Double,
        bearingDegrees: Float,
        distanceMeters: Double
    ): Pair<Double, Double> {
        val distRatio = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees.toDouble())
        val lat1Rad = Math.toRadians(startLat)
        val lon1Rad = Math.toRadians(startLon)

        val lat2Rad = asin(
            sin(lat1Rad) * cos(distRatio) +
                    cos(lat1Rad) * sin(distRatio) * cos(bearingRad)
        )

        val lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(distRatio) * cos(lat1Rad),
            cos(distRatio) - sin(lat1Rad) * sin(lat2Rad)
        )

        val destLat = Math.toDegrees(lat2Rad)
        val destLon = (Math.toDegrees(lon2Rad) + 540.0) % 360.0 - 180.0

        return Pair(destLat, destLon)
    }

    /**
     * Interpolates linearly between two coordinates by a given fraction [0.0, 1.0].
     */
    fun interpolate(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        fraction: Double
    ): Pair<Double, Double> {
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val lat = startLat + (endLat - startLat) * clampedFraction
        val lon = startLon + (endLon - startLon) * clampedFraction
        return Pair(lat, lon)
    }
}
