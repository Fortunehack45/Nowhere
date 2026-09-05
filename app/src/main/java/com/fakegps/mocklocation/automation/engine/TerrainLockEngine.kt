package com.fakegps.mocklocation.automation.engine

import android.content.Context
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.util.LocationNameResolver
import java.util.concurrent.ConcurrentHashMap

object TerrainLockEngine {

    enum class TerrainType {
        WALKABLE,
        WATER,
        BUILDING,
        RESTRICTED,
        UNKNOWN
    }

    sealed class TerrainStepResult {
        data class Accepted(val lat: Double, val lon: Double, val bearing: Float) : TerrainStepResult()
        data class Deflected(val lat: Double, val lon: Double, val bearing: Float, val deflectionAngleDeg: Float) : TerrainStepResult()
        data class Steered(val lat: Double, val lon: Double, val bearing: Float, val targetBearing: Float) : TerrainStepResult()
        data class HoldPosition(val reason: String) : TerrainStepResult()
    }

    // Deflection arc evaluation order (±15°, ±30°, ±45°)
    val DEFLECTION_ANGLES = listOf(15f, -15f, 30f, -30f, 45f, -45f)

    // Optional override classifier for unit testing
    var mockClassifier: ((Double, Double) -> TerrainType)? = null
    var mockNearestWalkableFinder: ((Double, Double, Double) -> Pair<Double, Double>?)? = null

    // In-memory tile / coordinate cache to prevent repeated geocoding on rapid steps
    private val classificationCache = ConcurrentHashMap<String, TerrainType>()

    fun clearCache() {
        classificationCache.clear()
    }

    private fun cacheKey(lat: Double, lon: Double): String {
        // Quantize to ~5 decimal places (~1.1 meter resolution)
        val qLat = (lat * 100000).toLong()
        val qLon = (lon * 100000).toLong()
        return "$qLat,$qLon"
    }

    /**
     * Classifies a global coordinate into a terrain type.
     */
    suspend fun classifyCoordinate(context: Context?, lat: Double, lon: Double): TerrainType {
        mockClassifier?.let { return it(lat, lon) }

        val key = cacheKey(lat, lon)
        classificationCache[key]?.let { return it }

        // Water detection via LocationNameResolver OSM reverse geocoding
        val isWater = if (context != null) {
            LocationNameResolver.isWaterCoordinate(context, lat, lon)
        } else false

        val type = if (isWater) {
            TerrainType.WATER
        } else {
            // Unclassified open land/road is treated as WALKABLE
            TerrainType.WALKABLE
        }

        classificationCache[key] = type
        return type
    }

    /**
     * Normalizes an angular difference into the range [-180, +180].
     */
    fun normalizeDeltaDegrees(diff: Float): Float {
        return (diff % 360f + 540f) % 360f - 180f
    }

    /**
     * Finds the nearest walkable coordinate within the given search radius (meters).
     */
    suspend fun findNearestWalkableCoordinate(
        context: Context?,
        lat: Double,
        lon: Double,
        searchRadiusMeters: Double
    ): Pair<Double, Double>? {
        mockNearestWalkableFinder?.let { return it(lat, lon, searchRadiusMeters) }

        // Sample 8 radial points at searchRadius / 2 and searchRadius
        val sampleDistances = listOf(searchRadiusMeters * 0.5, searchRadiusMeters)
        val sampleAngles = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

        for (dist in sampleDistances) {
            for (angle in sampleAngles) {
                val (sampleLat, sampleLon) = GeoUtils.computeDestinationPoint(lat, lon, angle, dist)
                if (classifyCoordinate(context, sampleLat, sampleLon) == TerrainType.WALKABLE) {
                    return Pair(sampleLat, sampleLon)
                }
            }
        }
        return null
    }

    /**
     * Evaluates a projected step from (currentLat, currentLon) along currentHeading for stepDistanceMeters.
     * Applies obstacle classification, deflection arc, gradual steering blending, or holding position.
     *
     * @param steeringFactor k in range [0.15, 0.30] for theta_{n+1} = theta_n + k * (theta_target - theta_n)
     */
    suspend fun evaluateStep(
        context: Context?,
        currentLat: Double,
        currentLon: Double,
        currentHeading: Float,
        stepDistanceMeters: Double,
        checkRestricted: Boolean = false,
        searchRadiusMeters: Double = 25.0,
        allowUnmapped: Boolean = true,
        steeringFactor: Float = 0.25f
    ): TerrainStepResult {
        // 1. Project next point using current heading and step distance
        val (projLat, projLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, currentHeading, stepDistanceMeters)
        val projType = classifyCoordinate(context, projLat, projLon)

        // 2. Check for normal acceptance
        if (projType == TerrainType.WALKABLE) {
            return TerrainStepResult.Accepted(projLat, projLon, currentHeading)
        }

        if (projType == TerrainType.UNKNOWN) {
            return if (allowUnmapped) {
                TerrainStepResult.Accepted(projLat, projLon, currentHeading)
            } else {
                TerrainStepResult.HoldPosition("Unmapped area restricted (strict mode)")
            }
        }

        val isObstacle = projType == TerrainType.WATER ||
                projType == TerrainType.BUILDING ||
                (checkRestricted && projType == TerrainType.RESTRICTED)

        if (!isObstacle) {
            return TerrainStepResult.Accepted(projLat, projLon, currentHeading)
        }

        // 3. Step is blocked by obstacle. Try deflection arc (±15°, ±30°, ±45°)
        for (deflection in DEFLECTION_ANGLES) {
            val candidateHeading = (currentHeading + deflection + 360f) % 360f
            val (candLat, candLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, candidateHeading, stepDistanceMeters)
            val candType = classifyCoordinate(context, candLat, candLon)
            if (candType == TerrainType.WALKABLE) {
                return TerrainStepResult.Deflected(candLat, candLon, candidateHeading, deflection)
            }
        }

        // 4. Deflection arc failed. Try gradual steering toward nearest walkable way
        val nearestWalkable = findNearestWalkableCoordinate(context, currentLat, currentLon, searchRadiusMeters)
        if (nearestWalkable != null) {
            val targetHeading = GeoUtils.calculateBearing(currentLat, currentLon, nearestWalkable.first, nearestWalkable.second)
            val delta = normalizeDeltaDegrees(targetHeading - currentHeading)
            val k = steeringFactor.coerceIn(0.15f, 0.30f)
            val blendedHeading = (currentHeading + k * delta + 360f) % 360f
            val (steeredLat, steeredLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, blendedHeading, stepDistanceMeters)
            return TerrainStepResult.Steered(steeredLat, steeredLon, blendedHeading, targetHeading)
        }

        // 5. No walkable path within search radius. Hold position!
        return TerrainStepResult.HoldPosition("TERRAIN_BLOCKED: Obstacle within radius, no walkable passage found")
    }
}
