package com.fakegps.mocklocation.simulator

import com.fakegps.mocklocation.engine.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

object RoadRouter {

    private val ROUTING_ENDPOINTS = listOf(
        "https://router.project-osrm.org/route/v1",
        "https://routing.openstreetmap.de/routed-car/route/v1"
    )

    /**
     * Resolves true real-world street & highway routing for both short and long-distance cross-country trips.
     * Stitches real motorway coordinates together, and uses natural spline curves when completely offline.
     */
    suspend fun resolveRealWorldRoute(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext waypoints

        // For Aircraft and Ship, generate smooth great-circle flight/marine paths
        if (mode == TransportMode.AIRCRAFT || mode == TransportMode.SHIP) {
            return@withContext interpolateLongDistanceGeodesic(waypoints, mode)
        }

        val profile = if (mode == TransportMode.FOOT) "walking" else "driving"

        // Try direct multi-point route first
        val directRoute = tryFetchOsrmRoute(waypoints, profile, mode)
        if (directRoute.size >= 2) {
            return@withContext downsampleWaypointsIfNeeded(directRoute, maxPoints = 5000)
        }

        // If direct long-distance query fails, chunk into sequential legs (e.g. waypoint pairs)
        val stitchedRoute = mutableListOf<RoutePoint>()

        for (i in 0 until waypoints.size - 1) {
            val legStart = waypoints[i]
            val legEnd = waypoints[i + 1]
            val legPoints = listOf(legStart, legEnd)

            val legResult = tryFetchOsrmRoute(legPoints, profile, mode)
            if (legResult.size >= 2) {
                if (stitchedRoute.isNotEmpty() && stitchedRoute.last() == legResult.first()) {
                    stitchedRoute.addAll(legResult.subList(1, legResult.size))
                } else {
                    stitchedRoute.addAll(legResult)
                }
            } else {
                // Generate natural road spline for this specific leg so it never defaults to a flat straight line!
                val splineLeg = generateNaturalRoadSpline(legStart, legEnd, mode)
                if (stitchedRoute.isNotEmpty() && stitchedRoute.last() == splineLeg.first()) {
                    stitchedRoute.addAll(splineLeg.subList(1, splineLeg.size))
                } else {
                    stitchedRoute.addAll(splineLeg)
                }
            }
        }

        if (stitchedRoute.size >= 2) {
            return@withContext downsampleWaypointsIfNeeded(stitchedRoute, maxPoints = 5000)
        }

        // Offline or across water: generate natural curving road path
        return@withContext generateNaturalRoadRoute(waypoints, mode)
    }

    private fun tryFetchOsrmRoute(
        points: List<RoutePoint>,
        profile: String,
        mode: TransportMode
    ): List<RoutePoint> {
        val coordinatesParam = points.joinToString(";") { "${it.longitude},${it.latitude}" }

        for (baseUrl in ROUTING_ENDPOINTS) {
            try {
                val urlString = "$baseUrl/$profile/$coordinatesParam?overview=full&geometries=geojson"
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "NowhereLocationSimulator/1.0")
                }

                if (connection.responseCode == 200) {
                    val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonStr)
                    if (root.optString("code") == "Ok") {
                        val routes = root.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val snappedPoints = mutableListOf<RoutePoint>()
                            for (i in 0 until coordinates.length()) {
                                val coordPair = coordinates.getJSONArray(i)
                                val lon = coordPair.getDouble(0)
                                val lat = coordPair.getDouble(1)
                                snappedPoints.add(RoutePoint(lat, lon, mode.defaultAltitudeMeters))
                            }
                            if (snappedPoints.size >= 2) {
                                return snappedPoints
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
                // Try next endpoint
            }
        }

        return emptyList()
    }

    /**
     * Generates a natural curving road trajectory using sinusoidal highway curvature simulation
     * so that even in full OFFLINE mode, routes follow realistic winding turns instead of flat lines.
     */
    fun generateNaturalRoadSpline(
        start: RoutePoint,
        end: RoutePoint,
        mode: TransportMode
    ): List<RoutePoint> {
        val dist = GeoUtils.calculateDistanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        val bearing = GeoUtils.calculateBearing(start.latitude, start.longitude, end.latitude, end.longitude)

        val result = mutableListOf<RoutePoint>()
        result.add(start)

        val stepMeters = (dist / 30.0).coerceIn(100.0, 5000.0)
        val steps = (dist / stepMeters).toInt().coerceIn(4, 60)

        // Lateral highway sway amplitude
        val swayMeters = (dist * 0.015).coerceIn(15.0, 400.0)

        for (i in 1 until steps) {
            val fraction = i.toDouble() / steps.toDouble()
            val (baseLat, baseLon) = GeoUtils.interpolate(start.latitude, start.longitude, end.latitude, end.longitude, fraction)

            // S-curve highway bend
            val lateralOffset = sin(fraction * Math.PI * 3.0) * swayMeters
            val lateralBearing = (bearing + 90.0f) % 360.0f

            val (offsetLat, offsetLon) = GeoUtils.computeDestinationPoint(baseLat, baseLon, lateralBearing, lateralOffset)
            val alt = start.altitude + (end.altitude - start.altitude) * fraction

            result.add(RoutePoint(offsetLat, offsetLon, if (alt > 0.1) alt else mode.defaultAltitudeMeters))
        }

        result.add(end)
        return result
    }

    private fun generateNaturalRoadRoute(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in 0 until waypoints.size - 1) {
            val leg = generateNaturalRoadSpline(waypoints[i], waypoints[i + 1], mode)
            if (result.isNotEmpty() && result.last() == leg.first()) {
                result.addAll(leg.subList(1, leg.size))
            } else {
                result.addAll(leg)
            }
        }
        return result
    }

    private fun interpolateLongDistanceGeodesic(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val legDistance = GeoUtils.calculateDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

            result.add(p1)

            if (legDistance > 10_000.0) {
                val stepSizeMeters = (legDistance / 50.0).coerceIn(10_000.0, 50_000.0)
                val numSubsteps = (legDistance / stepSizeMeters).toInt().coerceIn(2, 80)
                for (step in 1 until numSubsteps) {
                    val fraction = step.toDouble() / numSubsteps.toDouble()
                    val (interLat, interLon) = GeoUtils.interpolateGreatCircle(
                        p1.latitude, p1.longitude,
                        p2.latitude, p2.longitude,
                        fraction
                    )
                    val interAlt = p1.altitude + (p2.altitude - p1.altitude) * fraction
                    result.add(RoutePoint(interLat, interLon, if (interAlt > 0.1) interAlt else mode.defaultAltitudeMeters))
                }
            }
        }
        if (waypoints.isNotEmpty()) {
            result.add(waypoints.last())
        }
        return result
    }

    private fun downsampleWaypointsIfNeeded(points: List<RoutePoint>, maxPoints: Int = 5000): List<RoutePoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints.toDouble()
        val downsampled = mutableListOf<RoutePoint>()
        var index = 0.0
        while (index < points.size) {
            downsampled.add(points[index.toInt()])
            index += step
        }
        if (downsampled.last() != points.last()) {
            downsampled.add(points.last())
        }
        return downsampled
    }
}
