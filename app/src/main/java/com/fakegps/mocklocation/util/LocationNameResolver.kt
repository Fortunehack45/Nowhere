package com.fakegps.mocklocation.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.*
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
        val cached = cache[cacheKey]
        return if (!cached.isNullOrBlank() && !cached.matches(Regex("^[0-9\\-+, .°]+$"))) cached else null
    }

    fun setCachedLocationName(latitude: Double, longitude: Double, name: String) {
        if (name.isNotBlank() && !name.matches(Regex("^[0-9\\-+, .°]+$"))) {
            val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
            cache[cacheKey] = name
        }
    }

    fun resolveLocationNameAsync(
        context: Context,
        latitude: Double,
        longitude: Double,
        onResolved: (String) -> Unit
    ) {
        val cached = getCachedLocationName(latitude, longitude)
        if (!cached.isNullOrBlank()) {
            onResolved(cached)
            return
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val resolved = resolveLocationName(context, latitude, longitude)
            if (resolved.isNotBlank() && !resolved.matches(Regex("^[0-9\\-+, .°]+$"))) {
                withContext(Dispatchers.Main) {
                    onResolved(resolved)
                }
            }
        }
    }

    fun getCachedWaterStatus(latitude: Double, longitude: Double): Boolean? {
        val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
        return waterCache[cacheKey]
    }

    suspend fun resolveLocationName(context: Context, latitude: Double, longitude: Double): String =
        withContext(Dispatchers.IO) {
            val cacheKey = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
            cache[cacheKey]?.let {
                if (it.isNotBlank() && !it.matches(Regex("^[0-9\\-+, .°]+$"))) {
                    return@withContext it
                }
            }

            // 1. Android Native Geocoder
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
                        synchronized(lock) {
                            if (resolvedName == null) {
                                lock.wait(1200)
                            }
                        }
                        if (!resolvedName.isNullOrBlank() && !resolvedName!!.matches(Regex("^[0-9\\-+, .°]+$"))) {
                            cache[cacheKey] = resolvedName!!
                            return@withContext resolvedName!!
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val name = formatAddress(addresses[0])
                            if (name.isNotBlank() && !name.matches(Regex("^[0-9\\-+, .°]+$"))) {
                                cache[cacheKey] = name
                                return@withContext name
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 2. OpenStreetMap Nominatim Reverse Geocoder Fallback
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=14&addressdetails=1"
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "NowhereApp/1.0 (contact: support@nowhereapp.internal)")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val addr = json.optJSONObject("address")
                    val name = json.optString("name").ifBlank { null }
                    if (addr != null) {
                        val road = addr.optString("road").ifBlank { null }
                        val suburb = addr.optString("suburb").ifBlank { addr.optString("neighbourhood").ifBlank { null } }
                        val city = addr.optString("city").ifBlank {
                            addr.optString("town").ifBlank {
                                addr.optString("village").ifBlank {
                                    addr.optString("county").ifBlank {
                                        addr.optString("state").ifBlank { null }
                                    }
                                }
                            }
                        }
                        val country = addr.optString("country").ifBlank { null }

                        val poi = name ?: road ?: suburb
                        val result = when {
                            poi != null && city != null && poi != city -> "$poi, $city"
                            city != null && country != null -> "$city, $country"
                            city != null -> city
                            country != null -> country
                            else -> json.optString("display_name")
                        }
                        if (result.isNotBlank() && !result.matches(Regex("^[0-9\\-+, .°]+$"))) {
                            cache[cacheKey] = result
                            return@withContext result
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 3. BigDataCloud Free Client Reverse Geocoding Fallback
            try {
                val urlString = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$latitude&longitude=$longitude&localityLanguage=en"
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "NowhereApp/1.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val locality = json.optString("locality").ifBlank { json.optString("city") }
                    val city = json.optString("city").ifBlank { json.optString("principalSubdivision") }
                    val country = json.optString("countryName")

                    val result = when {
                        locality.isNotBlank() && city.isNotBlank() && locality != city -> "$locality, $city"
                        city.isNotBlank() && country.isNotBlank() -> "$city, $country"
                        city.isNotBlank() -> city
                        country.isNotBlank() -> country
                        else -> ""
                    }
                    if (result.isNotBlank() && !result.matches(Regex("^[0-9\\-+, .°]+$"))) {
                        cache[cacheKey] = result
                        return@withContext result
                    }
                }
            } catch (ignored: Exception) {}

            // 4. Default to formatted coordinates if completely offline (do not cache so retry is possible)
            return@withContext String.format(Locale.US, "%.4f°, %.4f°", latitude, longitude)
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
                        } else {
                            waterCache[cacheKey] = false
                            return@withContext false
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 2. OpenStreetMap Nominatim reverse check
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=14"
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 2500
                    setRequestProperty("User-Agent", "NowhereMarineValidator/1.0 (contact: support@nowhereapp.internal)")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val category = json.optString("category")
                    val type = json.optString("type")
                    val addressObj = json.optJSONObject("address")

                    val isWaterTag = category == "natural" && (type == "water" || type == "bay" || type == "coastline" || type == "beach") ||
                            category == "waterway" || type == "sea" || type == "ocean" || type == "lake" || type == "harbour"

                    val isWater = isWaterTag
                    waterCache[cacheKey] = isWater
                    return@withContext isWater
                }
            } catch (ignored: Exception) {}

            // Default fallback: Unmapped or offline points must default to walkable land (false) so motion sync does not lock up
            waterCache[cacheKey] = false
            return@withContext false
        }

    fun formatAddress(address: Address): String {
        val locality = address.locality ?: address.subAdminArea ?: address.adminArea
        val thoroughfare = address.thoroughfare
        val feature = address.featureName
        val subLocality = address.subLocality
        val country = address.countryName ?: address.countryCode

        val poiOrStreet = when {
            !feature.isNullOrBlank() && !feature.matches(Regex("^[0-9\\-+, .°]+$")) && feature != thoroughfare && feature != locality -> feature
            !thoroughfare.isNullOrBlank() && !thoroughfare.matches(Regex("^[0-9\\-+, .°]+$")) -> thoroughfare
            !subLocality.isNullOrBlank() && !subLocality.matches(Regex("^[0-9\\-+, .°]+$")) -> subLocality
            else -> null
        }

        return when {
            poiOrStreet != null && !locality.isNullOrBlank() -> "$poiOrStreet, $locality"
            !locality.isNullOrBlank() && !country.isNullOrBlank() -> "$locality, $country"
            !locality.isNullOrBlank() -> locality
            !country.isNullOrBlank() -> country
            else -> address.getAddressLine(0)?.replace(Regex("^Unnamed Road,?\\s*"), "") ?: ""
        }
    }
}
