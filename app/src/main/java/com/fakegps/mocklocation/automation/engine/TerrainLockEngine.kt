package com.fakegps.mocklocation.automation.engine

import android.content.Context
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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

    data class TerrainFeature(
        val type: TerrainType,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        fun contains(lat: Double, lon: Double): Boolean {
            return lat in minLat..maxLat && lon in minLon..maxLon
        }
    }

    val DEFLECTION_ANGLES = listOf(15f, -15f, 30f, -30f, 45f, -45f)

    var mockClassifier: ((Double, Double) -> TerrainType)? = null
    var mockNearestWalkableFinder: ((Double, Double, Double) -> Pair<Double, Double>?)? = null

    private val classificationCache = ConcurrentHashMap<String, TerrainType>()
    private val tileCache = ConcurrentHashMap<String, List<TerrainFeature>>()
    private val tileFetchInFlight = ConcurrentHashMap.newKeySet<String>()

    fun clearCache() {
        classificationCache.clear()
        tileCache.clear()
    }

    private fun cacheKey(lat: Double, lon: Double): String {
        val qLat = (lat * 20000).toLong()
        val qLon = (lon * 20000).toLong()
        return "$qLat,$qLon"
    }

    private fun tileKey(lat: Double, lon: Double): String {
        val qLat = (lat * 250).toLong()
        val qLon = (lon * 250).toLong()
        return "t$qLat,$qLon"
    }

    suspend fun classifyCoordinate(context: Context?, lat: Double, lon: Double): TerrainType {
        mockClassifier?.let { return it(lat, lon) }

        val key = cacheKey(lat, lon)
        classificationCache[key]?.let { return it }

        val tileFeatures = tileCache[tileKey(lat, lon)]
        if (tileFeatures != null) {
            val fromTile = classifyAgainstFeatures(lat, lon, tileFeatures)
            classificationCache[key] = fromTile
            return fromTile
        }

        if (context != null) {
            prefetchTile(context, lat, lon)
            val fetched = tileCache[tileKey(lat, lon)]
            if (fetched != null) {
                val fromTile = classifyAgainstFeatures(lat, lon, fetched)
                classificationCache[key] = fromTile
                return fromTile
            }
        }

        val fromNominatim = if (context != null) {
            classifyViaNominatim(context, lat, lon)
        } else {
            TerrainType.WALKABLE
        }
        classificationCache[key] = fromNominatim
        return fromNominatim
    }

    private fun classifyAgainstFeatures(lat: Double, lon: Double, features: List<TerrainFeature>): TerrainType {
        var water = false
        var building = false
        var restricted = false
        for (f in features) {
            if (!f.contains(lat, lon)) continue
            when (f.type) {
                TerrainType.WATER -> water = true
                TerrainType.BUILDING -> building = true
                TerrainType.RESTRICTED -> restricted = true
                else -> {}
            }
        }
        return when {
            water -> TerrainType.WATER
            restricted -> TerrainType.RESTRICTED
            building -> TerrainType.BUILDING
            else -> TerrainType.WALKABLE
        }
    }

    private suspend fun classifyViaNominatim(context: Context, lat: Double, lon: Double): TerrainType {
        val isWater = LocationNameResolver.isWaterCoordinate(context, lat, lon)
        return if (isWater) TerrainType.WATER else TerrainType.WALKABLE
    }

    private suspend fun prefetchTile(@Suppress("UNUSED_PARAMETER") context: Context, lat: Double, lon: Double) {
        val key = tileKey(lat, lon)
        if (tileCache.containsKey(key)) return
        if (!tileFetchInFlight.add(key)) return
        try {
            val features = fetchOverpassTile(lat, lon)
            tileCache[key] = features
        } catch (_: Exception) {
            tileCache.putIfAbsent(key, emptyList())
        } finally {
            tileFetchInFlight.remove(key)
        }
    }

    private suspend fun fetchOverpassTile(lat: Double, lon: Double): List<TerrainFeature> = withContext(Dispatchers.IO) {
        val delta = 0.0022 // ~240m
        val south = lat - delta
        val north = lat + delta
        val west = lon - delta
        val east = lon + delta
        val query = """
            [out:json][timeout:6];
            (
              way["natural"="water"]($south,$west,$north,$east);
              way["waterway"~"riverbank|dock|canal"]($south,$west,$north,$east);
              way["landuse"~"reservoir|basin"]($south,$west,$north,$east);
              way["building"]($south,$west,$north,$east);
              way["landuse"="military"]($south,$west,$north,$east);
              way["amenity"="prison"]($south,$west,$north,$east);
            );
            out bb;
        """.trimIndent()

        val url = URL("https://overpass-api.de/api/interpreter")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "NowhereTerrainLock/1.0 (Android)")
        }
        conn.outputStream.bufferedWriter().use { it.write("data=" + java.net.URLEncoder.encode(query, "UTF-8")) }
        if (conn.responseCode !in 200..299) return@withContext emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val elements = json.optJSONArray("elements") ?: return@withContext emptyList()
        val out = ArrayList<TerrainFeature>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val bounds = el.optJSONObject("bounds") ?: continue
            val tags = el.optJSONObject("tags")
            val type = classifyTags(tags)
            if (type == TerrainType.WALKABLE) continue
            val minLat = bounds.optDouble("minlat")
            val maxLat = bounds.optDouble("maxlat")
            val minLon = bounds.optDouble("minlon")
            val maxLon = bounds.optDouble("maxlon")
            if (abs(maxLat - minLat) > 0.08 || abs(maxLon - minLon) > 0.08) continue
            out.add(TerrainFeature(type, minLat, maxLat, minLon, maxLon))
        }
        out
    }

    private fun classifyTags(tags: JSONObject?): TerrainType {
        if (tags == null) return TerrainType.WALKABLE
        val natural = tags.optString("natural")
        val waterway = tags.optString("waterway")
        val landuse = tags.optString("landuse")
        val amenity = tags.optString("amenity")
        if (natural == "water" || landuse == "reservoir" || landuse == "basin" ||
            waterway == "riverbank" || waterway == "dock" || waterway == "canal"
        ) {
            return TerrainType.WATER
        }
        if (landuse == "military" || amenity == "prison") return TerrainType.RESTRICTED
        if (tags.has("building") && tags.optString("building") != "no") return TerrainType.BUILDING
        return TerrainType.WALKABLE
    }

    fun normalizeDeltaDegrees(diff: Float): Float {
        return (diff % 360f + 540f) % 360f - 180f
    }

    suspend fun findNearestWalkableCoordinate(
        context: Context?,
        lat: Double,
        lon: Double,
        searchRadiusMeters: Double
    ): Pair<Double, Double>? {
        mockNearestWalkableFinder?.let { return it(lat, lon, searchRadiusMeters) }

        val sampleDistances = listOf(searchRadiusMeters * 0.35, searchRadiusMeters * 0.7, searchRadiusMeters)
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
        val (projLat, projLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, currentHeading, stepDistanceMeters)
        val projType = classifyCoordinate(context, projLat, projLon)

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

        for (deflection in DEFLECTION_ANGLES) {
            val candidateHeading = (currentHeading + deflection + 360f) % 360f
            val (candLat, candLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, candidateHeading, stepDistanceMeters)
            val candType = classifyCoordinate(context, candLat, candLon)
            if (candType == TerrainType.WALKABLE) {
                return TerrainStepResult.Deflected(candLat, candLon, candidateHeading, deflection)
            }
        }

        val nearestWalkable = findNearestWalkableCoordinate(context, currentLat, currentLon, searchRadiusMeters)
        if (nearestWalkable != null) {
            val targetHeading = GeoUtils.calculateBearing(currentLat, currentLon, nearestWalkable.first, nearestWalkable.second)
            val delta = normalizeDeltaDegrees(targetHeading - currentHeading)
            val k = steeringFactor.coerceIn(0.15f, 0.30f)
            val blendedHeading = (currentHeading + k * delta + 360f) % 360f
            val (steeredLat, steeredLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, blendedHeading, stepDistanceMeters)
            val steeredType = classifyCoordinate(context, steeredLat, steeredLon)
            return if (steeredType == TerrainType.WALKABLE || (steeredType == TerrainType.UNKNOWN && allowUnmapped)) {
                TerrainStepResult.Steered(steeredLat, steeredLon, blendedHeading, targetHeading)
            } else {
                TerrainStepResult.HoldPosition("TERRAIN_BLOCKED: steered step still in ${steeredType.name}")
            }
        }

        return TerrainStepResult.HoldPosition("TERRAIN_BLOCKED: Obstacle within radius, no walkable passage found")
    }
}
