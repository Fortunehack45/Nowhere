package com.fakegps.mocklocation.simulator

import android.content.Context
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

object RoadRouter {

    private data class RoutingEndpoint(
        val baseUrl: String,
        val carProfile: String,
        val footProfile: String
    )

    private val ROUTING_ENDPOINTS = listOf(
        RoutingEndpoint(
            baseUrl = "https://routing.openstreetmap.de/routed-car/route/v1",
            carProfile = "driving",
            footProfile = "driving"
        ),
        RoutingEndpoint(
            baseUrl = "https://routing.openstreetmap.de/routed-foot/route/v1",
            carProfile = "driving",
            footProfile = "driving"
        ),
        RoutingEndpoint(
            baseUrl = "https://router.project-osrm.org/route/v1",
            carProfile = "driving",
            footProfile = "walking"
        )
    )

    /**
     * Validates whether waypoints for Ship mode are placed on water rather than land.
     * Returns Pair(isValid, errorMessageIfInvalid).
     */
    suspend fun validateMarineRoute(context: Context, waypoints: List<RoutePoint>): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            if (waypoints.isEmpty()) return@withContext Pair(false, "No waypoints plotted.")
            for ((idx, wp) in waypoints.withIndex()) {
                val isWater = LocationNameResolver.isWaterCoordinate(context, wp.latitude, wp.longitude)
                if (!isWater) {
                    return@withContext Pair(
                        false,
                        "Ship navigation is restricted to water bodies (oceans, seas, lakes, rivers). Waypoint #${idx + 1} (${String.format("%.4f", wp.latitude)}, ${String.format("%.4f", wp.longitude)}) is on dry land."
                    )
                }
            }
            return@withContext Pair(true, null)
        }

    /**
     * Resolves the shortest real-world route between waypoints based on transport mode:
     * - VEHICLE / FOOT: Follows shortest real roads/streets via OSRM, or shortest direct geodesic path when offline.
     * - AIRCRAFT: Follows shortest Great-Circle flight paths with realistic climb, cruise (9500m), and descent curves.
     * - SHIP: Follows direct marine waterways and ocean corridors locked strictly to sea level (0m).
     */
    suspend fun resolveRealWorldRoute(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext waypoints

        // For Aircraft, generate Great-Circle flight corridor with climb and descent profile
        if (mode == TransportMode.AIRCRAFT) {
            return@withContext generateFlightCorridor(waypoints)
        }

        // For Ship, generate marine waterway geodesic route locked strictly to sea level (0.0m)
        if (mode == TransportMode.SHIP) {
            return@withContext generateMarineWaterwayRoute(waypoints)
        }

        // Try direct multi-point road route first
        val directRoute = tryFetchOsrmRoute(waypoints, mode)
        if (directRoute.size >= 2) {
            return@withContext downsampleWaypointsIfNeeded(directRoute, maxPoints = 5000)
        }

        // If direct query fails, stitch leg by leg
        val stitchedRoute = mutableListOf<RoutePoint>()
        for (i in 0 until waypoints.size - 1) {
            val legStart = waypoints[i]
            val legEnd = waypoints[i + 1]
            val legPoints = listOf(legStart, legEnd)

            val legResult = tryFetchOsrmRoute(legPoints, mode)
            if (legResult.size >= 2) {
                if (stitchedRoute.isNotEmpty() && stitchedRoute.last() == legResult.first()) {
                    stitchedRoute.addAll(legResult.subList(1, legResult.size))
                } else {
                    stitchedRoute.addAll(legResult)
                }
            } else {
                // Shortest direct geodesic path between the two points
                val directLeg = generateShortestDirectPath(legStart, legEnd, mode)
                if (stitchedRoute.isNotEmpty() && stitchedRoute.last() == directLeg.first()) {
                    stitchedRoute.addAll(directLeg.subList(1, directLeg.size))
                } else {
                    stitchedRoute.addAll(directLeg)
                }
            }
        }

        if (stitchedRoute.size >= 2) {
            return@withContext downsampleWaypointsIfNeeded(stitchedRoute, maxPoints = 5000)
        }

        // Fallback: direct shortest geodesic route connecting all waypoints cleanly
        return@withContext generateShortestRoute(waypoints, mode)
    }

    /**
     * Generates a realistic flight corridor with 3-phase altitude simulation:
     * 1. Climb Phase (from ground to cruising altitude of 9,500m FL310)
     * 2. Cruise Phase (trans-continental Great-Circle navigation at FL310)
     * 3. Descent Phase (glide slope landing down to destination elevation)
     */
    private fun generateFlightCorridor(waypoints: List<RoutePoint>): List<RoutePoint> {
        val totalFlightPoints = mutableListOf<RoutePoint>()
        val cruiseAltitudeMeters = 9500.0

        for (i in 0 until waypoints.size - 1) {
            val start = waypoints[i]
            val end = waypoints[i + 1]
            val legDistance = GeoUtils.calculateDistanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)

            val stepSizeMeters = (legDistance / 60.0).coerceIn(5_000.0, 40_000.0)
            val numSteps = (legDistance / stepSizeMeters).toInt().coerceIn(4, 120)

            val isFirstLeg = (i == 0)
            val isLastLeg = (i == waypoints.size - 2)

            for (step in 0 until numSteps) {
                val fraction = step.toDouble() / numSteps.toDouble()
                val (lat, lon) = GeoUtils.interpolateGreatCircle(
                    start.latitude, start.longitude,
                    end.latitude, end.longitude,
                    fraction
                )

                val alt = when {
                    isFirstLeg && fraction < 0.25 -> {
                        val climbFraction = fraction / 0.25
                        start.altitude + (cruiseAltitudeMeters - start.altitude) * sin(climbFraction * Math.PI / 2.0)
                    }
                    isLastLeg && fraction > 0.75 -> {
                        val descentFraction = (fraction - 0.75) / 0.25
                        cruiseAltitudeMeters - (cruiseAltitudeMeters - end.altitude) * sin(descentFraction * Math.PI / 2.0)
                    }
                    else -> cruiseAltitudeMeters
                }

                totalFlightPoints.add(RoutePoint(lat, lon, alt))
            }
        }

        if (waypoints.isNotEmpty()) {
            val last = waypoints.last()
            totalFlightPoints.add(RoutePoint(last.latitude, last.longitude, last.altitude))
        }

        return totalFlightPoints
    }

    /**
     * Generates a marine waterway route strictly locked to sea level (altitude 0.0m)
     * with ocean Great-Circle interpolation between maritime coordinates.
     */
    private fun generateMarineWaterwayRoute(waypoints: List<RoutePoint>): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val legDistance = GeoUtils.calculateDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

            result.add(RoutePoint(p1.latitude, p1.longitude, 0.0))

            val stepSizeMeters = (legDistance / 40.0).coerceIn(1_000.0, 20_000.0)
            val numSteps = (legDistance / stepSizeMeters).toInt().coerceIn(2, 60)

            for (step in 1 until numSteps) {
                val fraction = step.toDouble() / numSteps.toDouble()
                val (interLat, interLon) = GeoUtils.interpolateGreatCircle(
                    p1.latitude, p1.longitude,
                    p2.latitude, p2.longitude,
                    fraction
                )
                result.add(RoutePoint(interLat, interLon, 0.0))
            }
        }

        if (waypoints.isNotEmpty()) {
            val last = waypoints.last()
            result.add(RoutePoint(last.latitude, last.longitude, 0.0))
        }

        return result
    }

    private fun tryFetchOsrmRoute(
        points: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> {
        val coordinatesParam = points.joinToString(";") { "${it.longitude},${it.latitude}" }

        for (endpoint in ROUTING_ENDPOINTS) {
            try {
                val profile = if (mode == TransportMode.FOOT) endpoint.footProfile else endpoint.carProfile
                val urlString = "${endpoint.baseUrl}/$profile/$coordinatesParam?overview=full&geometries=geojson&continue_straight=default"
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Nowhere-Android-App/1.0")
                    setRequestProperty("Accept", "application/json")
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
            } catch (_: Exception) {
                // Try next routing endpoint
            }
        }

        return emptyList()
    }

    /**
     * Generates the shortest, straight direct geodesic path between two waypoints.
     * Guarantees zero artificial lateral sway/wobble.
     */
    fun generateShortestDirectPath(
        start: RoutePoint,
        end: RoutePoint,
        mode: TransportMode
    ): List<RoutePoint> {
        val dist = GeoUtils.calculateDistanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        val result = mutableListOf<RoutePoint>()
        result.add(start)

        // Interpolate in clean, uniform 10m-25m steps
        val stepMeters = (dist / 40.0).coerceIn(10.0, 500.0)
        val steps = (dist / stepMeters).toInt().coerceIn(2, 200)

        for (i in 1 until steps) {
            val fraction = i.toDouble() / steps.toDouble()
            val (interLat, interLon) = if (dist > 50_000.0) {
                GeoUtils.interpolateGreatCircle(start.latitude, start.longitude, end.latitude, end.longitude, fraction)
            } else {
                GeoUtils.interpolate(start.latitude, start.longitude, end.latitude, end.longitude, fraction)
            }
            val alt = start.altitude + (end.altitude - start.altitude) * fraction
            result.add(RoutePoint(interLat, interLon, if (alt > 0.1) alt else mode.defaultAltitudeMeters))
        }

        result.add(end)
        return result
    }

    /**
     * Generates shortest direct geodesic path connecting all waypoints.
     */
    fun generateShortestRoute(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in 0 until waypoints.size - 1) {
            val leg = generateShortestDirectPath(waypoints[i], waypoints[i + 1], mode)
            if (result.isNotEmpty() && result.last() == leg.first()) {
                result.addAll(leg.subList(1, leg.size))
            } else {
                result.addAll(leg)
            }
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
