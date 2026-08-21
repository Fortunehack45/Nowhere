package com.fakegps.mocklocation.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object LocationNameResolver {

    private val cache = ConcurrentHashMap<String, String>()
    private val waterCache = ConcurrentHashMap<String, Boolean>()

    fun getCachedLocationName(latitude: Double, longitude: Double): String? {
        val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
        return cache[cacheKey]
    }

    suspend fun resolveLocationName(context: Context, latitude: Double, longitude: Double): String =
        withContext(Dispatchers.IO) {
            val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
            cache[cacheKey]?.let { return@withContext it }

            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var resolvedName: String? = null
                        val lock = Object()
                        geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                            synchronized(lock) {
                                if (addresses.isNotEmpty()) {
                                    resolvedName = formatAddress(addresses[0])
                                }
                                lock.notifyAll()
                            }
                        }
                        // brief wait for callback
                        synchronized(lock) {
                            if (resolvedName == null) {
                                lock.wait(600)
                            }
                        }
                        if (!resolvedName.isNullOrBlank()) {
                            cache[cacheKey] = resolvedName!!
                            return@withContext resolvedName!!
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val name = formatAddress(addresses[0])
                            if (name.isNotBlank()) {
                                cache[cacheKey] = name
                                return@withContext name
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
                // Fallback to formatted coordinates if offline
            }

            val fallback = String.format(Locale.US, "%.4f°, %.4f°", latitude, longitude)
            cache[cacheKey] = fallback
            return@withContext fallback
        }

    /**
     * Determines whether a given coordinate is located in water/marine bodies
     * (oceans, seas, bays, gulfs, lakes, straits, canals, harbors) rather than dry land.
     */
    suspend fun isWaterCoordinate(context: Context, latitude: Double, longitude: Double): Boolean =
        withContext(Dispatchers.IO) {
            val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
            waterCache[cacheKey]?.let { return@withContext it }

            // 1. Android Geocoder probe
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.US)
                    val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var list: List<Address>? = null
                        val lock = Object()
                        geocoder.getFromLocation(latitude, longitude, 1) { res ->
                            synchronized(lock) {
                                list = res
                                lock.notifyAll()
                            }
                        }
                        synchronized(lock) {
                            if (list == null) lock.wait(500)
                        }
                        list
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitude, longitude, 1)
                    }

                    if (addresses != null && addresses.isNotEmpty()) {
                        val addr = addresses[0]
                        val thoroughfare = addr.thoroughfare
                        val subThoroughfare = addr.subThoroughfare
                        val feature = addr.featureName ?: ""
                        val locality = addr.locality ?: ""

                        // Check if address represents dry land infrastructure
                        val isLandStreet = !thoroughfare.isNullOrBlank() || !subThoroughfare.isNullOrBlank()
                        val isLandFeature = feature.matches(Regex(".*(Road|Street|St|Ave|Avenue|Blvd|Drive|Dr|Way|Lane|Ln|Court|Ct|Plaza|Highway|Hwy|Building|Apartment|School|Hospital).*", RegexOption.IGNORE_CASE))

                        if (isLandStreet || isLandFeature) {
                            waterCache[cacheKey] = false
                            return@withContext false
                        }

                        val waterKeywords = listOf("ocean", "sea", "bay", "gulf", "lake", "strait", "sound", "canal", "waterway", "river", "harbor", "port", "basin", "channel")
                        val isWaterNamed = waterKeywords.any { 
                            feature.contains(it, ignoreCase = true) || locality.contains(it, ignoreCase = true) 
                        }
                        if (isWaterNamed) {
                            waterCache[cacheKey] = true
                            return@withContext true
                        }
                    } else {
                        // Open ocean often returns zero geocoding results
                        waterCache[cacheKey] = true
                        return@withContext true
                    }
                }
            } catch (ignored: Exception) {}

            // 2. OpenStreetMap Nominatim reverse check
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=14"
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "NowhereMarineValidator/1.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val category = json.optString("category")
                    val type = json.optString("type")
                    val addressObj = json.optJSONObject("address")

                    val isWaterTag = category == "natural" && (type == "water" || type == "bay" || type == "coastline" || type == "beach") ||
                            category == "waterway" || type == "sea" || type == "ocean" || type == "lake" || type == "harbour"

                    val isLandAddress = addressObj != null && (
                            addressObj.has("road") || addressObj.has("house_number") ||
                            addressObj.has("building") || addressObj.has("amenity") ||
                            addressObj.has("residential") || addressObj.has("suburb")
                    )

                    val isWater = isWaterTag || (!isLandAddress && addressObj == null)
                    waterCache[cacheKey] = isWater
                    return@withContext isWater
                }
            } catch (ignored: Exception) {}

            // Default fallback
            waterCache[cacheKey] = true
            return@withContext true
        }

    private fun formatAddress(address: Address): String {
        val locality = address.locality ?: address.subAdminArea ?: address.adminArea
        val thoroughfare = address.thoroughfare ?: address.featureName
        val country = address.countryCode ?: address.countryName

        return when {
            !thoroughfare.isNullOrBlank() && !locality.isNullOrBlank() -> "$thoroughfare, $locality"
            !locality.isNullOrBlank() && !country.isNullOrBlank() -> "$locality, $country"
            !locality.isNullOrBlank() -> locality
            !address.countryName.isNullOrBlank() -> address.countryName
            else -> address.getAddressLine(0) ?: ""
        }
    }
}
