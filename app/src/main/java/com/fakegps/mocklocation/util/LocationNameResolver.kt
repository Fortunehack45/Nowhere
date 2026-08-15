package com.fakegps.mocklocation.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object LocationNameResolver {

    private val cache = ConcurrentHashMap<String, String>()

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
