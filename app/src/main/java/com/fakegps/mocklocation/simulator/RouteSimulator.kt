package com.fakegps.mocklocation.simulator

import com.fakegps.mocklocation.engine.GeoUtils
import kotlin.math.*

data class SimulatedLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val bearingDegrees: Float,
    val isCompleted: Boolean = false
)

class RouteSimulator(
    waypoints: List<RoutePoint>,
    var targetSpeedKmh: Float = 20.0f,
    var isLooping: Boolean = true
) {
    companion object {
        private const val MAX_ACCELERATION_MPS2 = 2.0 // 2 m/s² (realistic vehicle/jogger acceleration)
        private const val MAX_DECELERATION_MPS2 = 3.0 // 3 m/s² deceleration
        private const val MIN_CORNER_SPEED_MPS = 2.0f // Corner minimum speed ~7.2 km/h
    }

    private val rawWaypoints = if (waypoints.size >= 2) waypoints else emptyList()
    private val segments: List<RouteSegment>
    val totalDistanceMeters: Double

    private var currentDistanceAlongRoute: Double = 0.0
    private var currentSpeedMps: Float = 0.0f
    private var isPaused: Boolean = false
    private var isCompleted: Boolean = false

    private data class RouteSegment(
        val start: RoutePoint,
        val end: RoutePoint,
        val distanceMeters: Double,
        val startCumulativeDistance: Double,
        val bearing: Float,
        val turnAngleDeg: Float
    )

    init {
        val segmentList = mutableListOf<RouteSegment>()
        var cumulativeDist = 0.0

        if (rawWaypoints.size >= 2) {
            for (i in 0 until rawWaypoints.size - 1) {
                val p1 = rawWaypoints[i]
                val p2 = rawWaypoints[i + 1]
                val dist = GeoUtils.calculateDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
                val bearing = GeoUtils.calculateBearing(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

                val turnAngle = if (i > 0) {
                    val prevBearing = segmentList[i - 1].bearing
                    val diff = abs(bearing - prevBearing)
                    if (diff > 180f) 360f - diff else diff
                } else 0f

                segmentList.add(
                    RouteSegment(
                        start = p1,
                        end = p2,
                        distanceMeters = dist,
                        startCumulativeDistance = cumulativeDist,
                        bearing = bearing,
                        turnAngleDeg = turnAngle
                    )
                )
                cumulativeDist += dist
            }
        }
        segments = segmentList
        totalDistanceMeters = cumulativeDist
    }

    fun hasValidRoute(): Boolean = segments.isNotEmpty() && totalDistanceMeters > 0.1

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun isPaused(): Boolean = isPaused
    fun isFinished(): Boolean = isCompleted

    fun reset() {
        currentDistanceAlongRoute = 0.0
        currentSpeedMps = 0.0f
        isPaused = false
        isCompleted = false
    }

    /**
     * Advances simulation by [deltaTimeSeconds] and returns the calculated position, speed, and heading.
     */
    fun tick(deltaTimeSeconds: Double): SimulatedLocation? {
        if (!hasValidRoute() || isCompleted) return null
        if (isPaused) {
            val currentLoc = getLocationAtDistance(currentDistanceAlongRoute)
            return currentLoc.copy(speedMps = 0.0f)
        }

        val targetSpeedMps = (targetSpeedKmh * 1000f / 3600f).coerceAtLeast(0.5f)

        // Find active segment
        val segmentIndex = findSegmentIndexForDistance(currentDistanceAlongRoute)
        val activeSegment = segments[segmentIndex]

        // Calculate realistic speed constraint near upcoming turns or route end
        val desiredSpeedMps = calculateDesiredSpeed(segmentIndex, targetSpeedMps)

        // Smooth acceleration/deceleration
        if (currentSpeedMps < desiredSpeedMps) {
            currentSpeedMps = (currentSpeedMps + MAX_ACCELERATION_MPS2 * deltaTimeSeconds)
                .toFloat()
                .coerceAtMost(desiredSpeedMps)
        } else if (currentSpeedMps > desiredSpeedMps) {
            currentSpeedMps = (currentSpeedMps - MAX_DECELERATION_MPS2 * deltaTimeSeconds)
                .toFloat()
                .coerceAtLeast(desiredSpeedMps)
        }

        val distanceStep = currentSpeedMps * deltaTimeSeconds
        currentDistanceAlongRoute += distanceStep

        if (currentDistanceAlongRoute >= totalDistanceMeters) {
            if (isLooping) {
                currentDistanceAlongRoute %= totalDistanceMeters
            } else {
                currentDistanceAlongRoute = totalDistanceMeters
                isCompleted = true
                val endLoc = getLocationAtDistance(totalDistanceMeters)
                return endLoc.copy(speedMps = 0.0f, isCompleted = true)
            }
        }

        return getLocationAtDistance(currentDistanceAlongRoute)
    }

    private fun calculateDesiredSpeed(currentSegmentIndex: Int, baseTargetSpeed: Float): Float {
        // If coming to end of route and not looping, decelerate
        if (!isLooping) {
            val distToEnd = totalDistanceMeters - currentDistanceAlongRoute
            val stoppingDistance = (currentSpeedMps.pow(2)) / (2 * MAX_DECELERATION_MPS2)
            if (distToEnd <= stoppingDistance + 5.0) {
                return (sqrt(2 * MAX_DECELERATION_MPS2 * distToEnd)).toFloat().coerceAtLeast(0.2f)
            }
        }

        // Cornering speed reduction
        val nextSegmentIndex = currentSegmentIndex + 1
        if (nextSegmentIndex < segments.size) {
            val nextSegment = segments[nextSegmentIndex]
            if (nextSegment.turnAngleDeg > 35f) {
                val distToTurn = nextSegment.startCumulativeDistance - currentDistanceAlongRoute
                if (distToTurn in 0.0..20.0) {
                    val severity = (nextSegment.turnAngleDeg / 180.0).coerceIn(0.0, 1.0)
                    val cornerSpeed = baseTargetSpeed * (1.0 - 0.6 * severity).toFloat()
                    return max(MIN_CORNER_SPEED_MPS, cornerSpeed)
                }
            }
        }

        return baseTargetSpeed
    }

    private fun findSegmentIndexForDistance(distance: Double): Int {
        for (i in segments.indices) {
            val seg = segments[i]
            if (distance >= seg.startCumulativeDistance && distance <= seg.startCumulativeDistance + seg.distanceMeters) {
                return i
            }
        }
        return segments.lastIndex
    }

    private fun getLocationAtDistance(distance: Double): SimulatedLocation {
        val clampedDist = distance.coerceIn(0.0, totalDistanceMeters)
        val segIndex = findSegmentIndexForDistance(clampedDist)
        val seg = segments[segIndex]

        val fraction = if (seg.distanceMeters > 0.0001) {
            (clampedDist - seg.startCumulativeDistance) / seg.distanceMeters
        } else {
            0.0
        }

        val (lat, lon) = GeoUtils.interpolate(
            seg.start.latitude,
            seg.start.longitude,
            seg.end.latitude,
            seg.end.longitude,
            fraction
        )

        val alt = seg.start.altitude + (seg.end.altitude - seg.start.altitude) * fraction

        return SimulatedLocation(
            latitude = lat,
            longitude = lon,
            altitude = alt,
            speedMps = currentSpeedMps,
            bearingDegrees = seg.bearing,
            isCompleted = isCompleted
        )
    }
}
