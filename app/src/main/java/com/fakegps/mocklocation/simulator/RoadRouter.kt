package com.fakegps.mocklocation.simulator

import com.fakegps.mocklocation.engine.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RoadRouter {

    /**
     * Resolves real-world street routing for local driving/walking trips, or generates
     * smooth geodesic Great-Circle trajectory waypoints for long-distance and intercontinental routes.
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

        // Check if any leg is ultra-long distance (> 250 km or across sea/countries)
        var hasUltraLongLeg = false
        for (i in 0 until waypoints.size - 1) {
            val dist = GeoUtils.calculateDistanceMeters(
                waypoints[i].latitude, waypoints[i].longitude,
                waypoints[i + 1].latitude, waypoints[i + 1].longitude
            )
            if (dist > 250_000.0) {
                hasUltraLongLeg = true
                break
            }
        }

        // If ultra-long distance, OSRM public server will reject. Use smooth geodesic interpolation!
        if (hasUltraLongLeg) {
            return@withContext interpolateLongDistanceGeodesic(waypoints, mode)
        }

        try {
            val osrmProfile = if (mode == TransportMode.FOOT) "walking" else "driving"
            val coordinatesParam = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
            val urlString = "https://router.project-osrm.org/route/v1/$osrmProfile/$coordinatesParam?overview=full&geometries=geojson"

            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
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
                            return@withContext downsampleWaypointsIfNeeded(snappedPoints, maxPoints = 2500)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
            // Graceful fallback to geodesic waypoints
        }

        return@withContext interpolateLongDistanceGeodesic(waypoints, mode)
    }

    /**
     * Subdivides long straight spans into smooth spherical Great-Circle waypoints (e.g. every 20-50 km).
     * Guarantees that cross-country simulation runs with continuous, jitter-free physics.
     */
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

            // If span > 10 km, create intermediate points so navigation & simulation are fluid
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

    private fun downsampleWaypointsIfNeeded(points: List<RoutePoint>, maxPoints: Int = 2500): List<RoutePoint> {
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
