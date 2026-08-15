package com.fakegps.mocklocation.simulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RoadRouter {

    suspend fun resolveRealWorldRoute(
        waypoints: List<RoutePoint>,
        mode: TransportMode
    ): List<RoutePoint> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext waypoints

        // For Aircraft and Ship, we use direct great-circle waypoints
        if (mode == TransportMode.AIRCRAFT || mode == TransportMode.SHIP) {
            return@withContext waypoints
        }

        try {
            val osrmProfile = if (mode == TransportMode.FOOT) "walking" else "driving"
            val coordinatesParam = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
            val urlString = "https://router.project-osrm.org/route/v1/$osrmProfile/$coordinatesParam?overview=full&geometries=geojson"

            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
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
                            return@withContext snappedPoints
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
            // Graceful fallback to raw waypoints
        }

        return@withContext waypoints
    }
}
