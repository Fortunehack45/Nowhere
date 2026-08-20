package com.fakegps.mocklocation.simulator

import com.fakegps.mocklocation.engine.GeoUtils
import kotlin.math.*

data class SimulatedLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val bearingDegrees: Float,
    val isCompleted: Boolean = false,
    val totalDistanceMeters: Double = 0.0,
    val distanceCoveredMeters: Double = 0.0,
    val distanceRemainingMeters: Double = 0.0
)

class RouteSimulator(
    waypoints: List<RoutePoint>,
    var targetSpeedKmh: Float = 20.0f,
    var isLooping: Boolean = true,
    var transportMode: TransportMode = TransportMode.VEHICLE
) {
    private val rawWaypoints = if (waypoints.size >= 2) waypoints else emptyList()
    private val segments: List<RouteSegment>
    val totalDistanceMeters: Double

    private var currentDistanceAlongRoute: Double = 0.0
    private var currentSpeedMps: Float = 0.0f
    private var isPaused: Boolean = false
    private var isCompleted: Boolean = false
    private var cachedSegmentIndex: Int = 0

    // Waypoint Dwell / Stop State
    private data class WaypointStop(
        val index: Int,
        val point: RoutePoint,
        val cumulativeDistance: Double,
        val durationSeconds: Int
    )

    private val waypointStops: List<WaypointStop>
    private var dwellSecondsRemaining: Double = 0.0
    private var activeDwellPoint: RoutePoint? = null
    private val completedDwellIndices = mutableSetOf<Int>()

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
        val stopsList = mutableListOf<WaypointStop>()
        var cumulativeDist = 0.0

        val effectiveWaypoints = if (isLooping && rawWaypoints.size >= 2) {
            val pFirst = rawWaypoints.first()
            val pLast = rawWaypoints.last()
            val closingDist = GeoUtils.calculateDistanceMeters(pLast.latitude, pLast.longitude, pFirst.latitude, pFirst.longitude)
            if (closingDist > 25.0) {
                rawWaypoints + pFirst
            } else {
                rawWaypoints
            }
        } else {
            rawWaypoints
        }

        if (effectiveWaypoints.size >= 2) {
            for (i in 0 until effectiveWaypoints.size) {
                val pt = effectiveWaypoints[i]
                if (pt.stopDurationSeconds > 0) {
                    stopsList.add(WaypointStop(i, pt, cumulativeDist, pt.stopDurationSeconds))
                }

                if (i < effectiveWaypoints.size - 1) {
                    val nextPt = effectiveWaypoints[i + 1]
                    val dist = GeoUtils.calculateDistanceMeters(pt.latitude, pt.longitude, nextPt.latitude, nextPt.longitude)
                    val bearing = GeoUtils.calculateBearing(pt.latitude, pt.longitude, nextPt.latitude, nextPt.longitude)

                    val turnAngle = if (i > 0 && segmentList.isNotEmpty()) {
                        val prevBearing = segmentList[i - 1].bearing
                        val diff = abs(bearing - prevBearing)
                        if (diff > 180f) 360f - diff else diff
                    } else 0f

                    segmentList.add(
                        RouteSegment(
                            start = pt,
                            end = nextPt,
                            distanceMeters = dist,
                            startCumulativeDistance = cumulativeDist,
                            bearing = bearing,
                            turnAngleDeg = turnAngle
                        )
                    )
                    cumulativeDist += dist
                }
            }
        }
        segments = segmentList
        waypointStops = stopsList
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
        cachedSegmentIndex = 0
        dwellSecondsRemaining = 0.0
        activeDwellPoint = null
        completedDwellIndices.clear()
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

        // Handle active waypoint dwell / stop duration
        if (dwellSecondsRemaining > 0.0 && activeDwellPoint != null) {
            dwellSecondsRemaining = (dwellSecondsRemaining - deltaTimeSeconds).coerceAtLeast(0.0)
            val dwellPt = activeDwellPoint!!
            val remaining = max(0.0, totalDistanceMeters - currentDistanceAlongRoute)
            return SimulatedLocation(
                latitude = dwellPt.latitude,
                longitude = dwellPt.longitude,
                altitude = if (dwellPt.altitude > 0.1) dwellPt.altitude else transportMode.defaultAltitudeMeters,
                speedMps = 0.0f,
                bearingDegrees = calculateSmoothedBearing(cachedSegmentIndex, currentDistanceAlongRoute),
                isCompleted = false,
                totalDistanceMeters = totalDistanceMeters,
                distanceCoveredMeters = currentDistanceAlongRoute,
                distanceRemainingMeters = remaining
            )
        }

        val maxAccelerationMps2 = when (transportMode) {
            TransportMode.FOOT -> 1.2
            TransportMode.VEHICLE -> 2.5
            TransportMode.SHIP -> 0.6
            TransportMode.AIRCRAFT -> 6.0
        }

        val maxDecelerationMps2 = when (transportMode) {
            TransportMode.FOOT -> 2.0
            TransportMode.VEHICLE -> 3.5
            TransportMode.SHIP -> 0.8
            TransportMode.AIRCRAFT -> 4.5
        }

        val targetSpeedMps = (targetSpeedKmh * 1000f / 3600f).coerceAtLeast(0.2f)

        // Check if reaching an upcoming dwell stop waypoint
        for (stop in waypointStops) {
            if (stop.index !in completedDwellIndices) {
                if (abs(currentDistanceAlongRoute - stop.cumulativeDistance) <= 1.0 ||
                    (currentDistanceAlongRoute < stop.cumulativeDistance && currentDistanceAlongRoute + (currentSpeedMps * deltaTimeSeconds) >= stop.cumulativeDistance)
                ) {
                    completedDwellIndices.add(stop.index)
                    dwellSecondsRemaining = stop.durationSeconds.toDouble()
                    activeDwellPoint = stop.point
                    currentDistanceAlongRoute = stop.cumulativeDistance
                    currentSpeedMps = 0.0f
                    val remaining = max(0.0, totalDistanceMeters - currentDistanceAlongRoute)
                    return SimulatedLocation(
                        latitude = stop.point.latitude,
                        longitude = stop.point.longitude,
                        altitude = if (stop.point.altitude > 0.1) stop.point.altitude else transportMode.defaultAltitudeMeters,
                        speedMps = 0.0f,
                        bearingDegrees = calculateSmoothedBearing(cachedSegmentIndex, currentDistanceAlongRoute),
                        isCompleted = false,
                        totalDistanceMeters = totalDistanceMeters,
                        distanceCoveredMeters = currentDistanceAlongRoute,
                        distanceRemainingMeters = remaining
                    )
                }
            }
        }

        // Find active segment with high efficiency
        val segmentIndex = findSegmentIndexForDistance(currentDistanceAlongRoute)

        // Calculate realistic speed constraint near upcoming turns, dwell stops, or route end
        val desiredSpeedMps = calculateDesiredSpeed(segmentIndex, targetSpeedMps, maxDecelerationMps2)

        // Smooth acceleration/deceleration
        if (currentSpeedMps < desiredSpeedMps) {
            currentSpeedMps = (currentSpeedMps + maxAccelerationMps2 * deltaTimeSeconds)
                .toFloat()
                .coerceAtMost(desiredSpeedMps)
        } else if (currentSpeedMps > desiredSpeedMps) {
            currentSpeedMps = (currentSpeedMps - maxDecelerationMps2 * deltaTimeSeconds)
                .toFloat()
                .coerceAtLeast(desiredSpeedMps)
        }

        val distanceStep = currentSpeedMps * deltaTimeSeconds
        currentDistanceAlongRoute += distanceStep

        if (currentDistanceAlongRoute >= totalDistanceMeters) {
            if (isLooping) {
                currentDistanceAlongRoute %= totalDistanceMeters
                cachedSegmentIndex = 0
                completedDwellIndices.clear()
            } else {
                currentDistanceAlongRoute = totalDistanceMeters
                isCompleted = true
                val endLoc = getLocationAtDistance(totalDistanceMeters)
                return endLoc.copy(
                    speedMps = 0.0f,
                    isCompleted = true,
                    totalDistanceMeters = totalDistanceMeters,
                    distanceCoveredMeters = totalDistanceMeters,
                    distanceRemainingMeters = 0.0
                )
            }
        }

        val loc = getLocationAtDistance(currentDistanceAlongRoute)
        val remaining = max(0.0, totalDistanceMeters - currentDistanceAlongRoute)
        return loc.copy(
            totalDistanceMeters = totalDistanceMeters,
            distanceCoveredMeters = currentDistanceAlongRoute,
            distanceRemainingMeters = remaining
        )
    }

    private fun calculateDesiredSpeed(
        currentSegmentIndex: Int,
        baseTargetSpeed: Float,
        maxDeceleration: Double
    ): Float {
        // 1. Decelerate if approaching an upcoming dwell stop waypoint
        for (stop in waypointStops) {
            if (stop.index !in completedDwellIndices) {
                val distToStop = stop.cumulativeDistance - currentDistanceAlongRoute
                if (distToStop > 0.0) {
                    val stoppingDistance = (currentSpeedMps.pow(2)) / (2 * maxDeceleration)
                    if (distToStop <= stoppingDistance + 5.0) {
                        return (sqrt(2 * maxDeceleration * distToStop)).toFloat().coerceAtLeast(0.2f)
                    }
                }
            }
        }

        // 2. If coming to end of route and not looping, decelerate
        if (!isLooping) {
            val distToEnd = totalDistanceMeters - currentDistanceAlongRoute
            val stoppingDistance = (currentSpeedMps.pow(2)) / (2 * maxDeceleration)
            if (distToEnd <= stoppingDistance + 5.0) {
                return (sqrt(2 * maxDeceleration * distToEnd)).toFloat().coerceAtLeast(0.2f)
            }
        }

        // 3. Cornering speed reduction for land vehicles and ships (aircraft maintain bank speed)
        if (transportMode != TransportMode.AIRCRAFT) {
            // A. Check if we just came out of a sharp turn (exit zone speed hold)
            if (currentSegmentIndex in segments.indices) {
                val currentSegment = segments[currentSegmentIndex]
                if (currentSegment.turnAngleDeg > 35f) {
                    val distFromTurn = currentDistanceAlongRoute - currentSegment.startCumulativeDistance
                    val severity = (currentSegment.turnAngleDeg / 180.0).coerceIn(0.0, 1.0)
                    val cornerSpeed = baseTargetSpeed * (1.0 - 0.55 * severity).toFloat()
                    val exitDistance = max(15.0, (baseTargetSpeed * 1.0).toDouble())
                    if (distFromTurn in 0.0..exitDistance) {
                        return max(1.5f, cornerSpeed)
                    }
                }
            }

            // B. Check if approaching an upcoming sharp turn (braking zone with speed-derived distance)
            val nextSegmentIndex = currentSegmentIndex + 1
            if (nextSegmentIndex < segments.size) {
                val nextSegment = segments[nextSegmentIndex]
                if (nextSegment.turnAngleDeg > 35f) {
                    val distToTurn = nextSegment.startCumulativeDistance - currentDistanceAlongRoute
                    val severity = (nextSegment.turnAngleDeg / 180.0).coerceIn(0.0, 1.0)
                    val cornerSpeed = baseTargetSpeed * (1.0 - 0.55 * severity).toFloat()
                    val brakingDistance = (currentSpeedMps.pow(2) - cornerSpeed.pow(2)) / (2 * maxDeceleration)
                    val effectiveBrakingDistance = max(0.0, brakingDistance) + 5.0
                    if (distToTurn in 0.0..effectiveBrakingDistance) {
                        return max(1.5f, cornerSpeed)
                    }
                }
            }
        }

        return baseTargetSpeed
    }

    private fun findSegmentIndexForDistance(distance: Double): Int {
        if (segments.isEmpty()) return 0
        if (distance <= 0.0) return 0
        if (distance >= totalDistanceMeters) return segments.lastIndex

        // Fast sequential check from cached index (O(1) in continuous simulation)
        if (cachedSegmentIndex in segments.indices) {
            val currentSeg = segments[cachedSegmentIndex]
            if (distance >= currentSeg.startCumulativeDistance && distance <= currentSeg.startCumulativeDistance + currentSeg.distanceMeters) {
                return cachedSegmentIndex
            }
            if (cachedSegmentIndex + 1 in segments.indices) {
                val nextSeg = segments[cachedSegmentIndex + 1]
                if (distance >= nextSeg.startCumulativeDistance && distance <= nextSeg.startCumulativeDistance + nextSeg.distanceMeters) {
                    cachedSegmentIndex++
                    return cachedSegmentIndex
                }
            }
        }

        // Binary Search in O(log N) for arbitrary distance lookup
        var low = 0
        var high = segments.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val seg = segments[mid]
            val segEnd = seg.startCumulativeDistance + seg.distanceMeters
            if (distance < seg.startCumulativeDistance) {
                high = mid - 1
            } else if (distance > segEnd) {
                low = mid + 1
            } else {
                cachedSegmentIndex = mid
                return mid
            }
        }

        val fallbackIndex = low.coerceIn(0, segments.lastIndex)
        cachedSegmentIndex = fallbackIndex
        return fallbackIndex
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

        // For large spans (> 20km) or airborne/marine voyages, use geodesic Great-Circle interpolation
        val (lat, lon) = if (seg.distanceMeters > 20000.0 || transportMode == TransportMode.AIRCRAFT || transportMode == TransportMode.SHIP) {
            GeoUtils.interpolateGreatCircle(
                seg.start.latitude,
                seg.start.longitude,
                seg.end.latitude,
                seg.end.longitude,
                fraction
            )
        } else {
            GeoUtils.interpolate(
                seg.start.latitude,
                seg.start.longitude,
                seg.end.latitude,
                seg.end.longitude,
                fraction
            )
        }

        val baseAltitude = if (seg.start.altitude > 0.1 || seg.end.altitude > 0.1) {
            seg.start.altitude + (seg.end.altitude - seg.start.altitude) * fraction
        } else {
            transportMode.defaultAltitudeMeters
        }

        val calculatedBearing = calculateSmoothedBearing(segIndex, clampedDist)

        return SimulatedLocation(
            latitude = lat,
            longitude = lon,
            altitude = baseAltitude,
            speedMps = currentSpeedMps,
            bearingDegrees = calculatedBearing,
            isCompleted = isCompleted
        )
    }

    private fun calculateSmoothedBearing(segIndex: Int, distance: Double): Float {
        if (segments.isEmpty()) return 0f
        val seg = segments[segIndex]
        val distFromStart = distance - seg.startCumulativeDistance
        val distToEnd = (seg.startCumulativeDistance + seg.distanceMeters) - distance

        val maxHalfWindow = 7.5

        // Check incoming vertex transition (start of segment)
        val prevSeg = when {
            segIndex > 0 -> segments[segIndex - 1]
            isLooping && segments.size >= 2 -> segments.last()
            else -> null
        }
        if (prevSeg != null) {
            val halfWindow = min(maxHalfWindow, min(seg.distanceMeters, prevSeg.distanceMeters) / 2.0)
            if (distFromStart in 0.0..halfWindow && halfWindow > 0.001) {
                val blendFraction = ((distFromStart + halfWindow) / (2.0 * halfWindow)).toFloat().coerceIn(0.5f, 1.0f)
                return interpolateBearing(prevSeg.bearing, seg.bearing, blendFraction)
            }
        }

        // Check outgoing vertex transition (end of segment)
        val nextSeg = when {
            segIndex < segments.lastIndex -> segments[segIndex + 1]
            isLooping && segments.size >= 2 -> segments.first()
            else -> null
        }
        if (nextSeg != null) {
            val halfWindow = min(maxHalfWindow, min(seg.distanceMeters, nextSeg.distanceMeters) / 2.0)
            if (distToEnd in 0.0..halfWindow && halfWindow > 0.001) {
                val blendFraction = ((halfWindow - distToEnd) / (2.0 * halfWindow)).toFloat().coerceIn(0.0f, 0.5f)
                return interpolateBearing(seg.bearing, nextSeg.bearing, blendFraction)
            }
        }

        return seg.bearing
    }

    private fun interpolateBearing(b1: Float, b2: Float, fraction: Float): Float {
        var diff = (b2 - b1) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val res = (b1 + diff * fraction) % 360f
        return if (res < 0f) res + 360f else res
    }
}
