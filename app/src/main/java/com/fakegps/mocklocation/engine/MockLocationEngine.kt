package com.fakegps.mocklocation.engine

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences

/**
 * Low-level mock location provider engine interfacing directly with Android's LocationManager.
 * Supports GPS_PROVIDER, NETWORK_PROVIDER, and Google Play FUSED_PROVIDER.
 */
class MockLocationEngine(
    private val context: Context,
    private val realismLayer: RealismLayer = RealismLayer(),
    private val settingsPrefs: AppSettingsPreferences = AppSettingsPreferences(context)
) {
    companion object {
        private const val TAG = "MockLocationEngine"
        const val FUSED_PROVIDER_NAME = "fused"
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var isInitialized = false

    private fun getActiveProviders(): List<String> {
        val list = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (settingsPrefs.useFusedProvider) {
            list.add(FUSED_PROVIDER_NAME)
        }
        return list
    }

    /**
     * Initializes the test providers.
     */
    @Synchronized
    fun initialize(): Result<Unit> {
        if (isInitialized) return Result.success(Unit)

        val providers = getActiveProviders()
        for (provider in providers) {
            try {
                registerTestProvider(provider)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering test provider $provider: not set as mock app", e)
                isInitialized = false
                return Result.failure(MockLocationError.NotSelectedAsMockApp(cause = e))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider cannot be registered or already exists: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error registering $provider", e)
            }
        }

        isInitialized = true
        return Result.success(Unit)
    }

    private fun registerTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setHasNetworkRequirement(provider == LocationManager.NETWORK_PROVIDER)
                .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()

            locationManager.addTestProvider(
                provider,
                properties,
                emptySet()
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                provider == LocationManager.NETWORK_PROVIDER,
                provider == LocationManager.GPS_PROVIDER,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        }

        locationManager.setTestProviderEnabled(provider, true)
    }

    /**
     * Injects a spoofed coordinate into the OS location system for all registered providers.
     */
    @Synchronized
    fun setLocation(
        latitude: Double,
        longitude: Double,
        altitude: Double = 15.0,
        speed: Float = 0.0f,
        bearing: Float = 0.0f,
        applyStationaryJitter: Boolean = false
    ): Result<Location> {
        val initResult = if (!isInitialized) initialize() else Result.success(Unit)
        if (initResult.isFailure) {
            return Result.failure(initResult.exceptionOrNull() ?: MockLocationError.InternalError("Init failed"))
        }

        val (finalLat, finalLon) = if (applyStationaryJitter && speed < 0.1f) {
            realismLayer.applyJitter(latitude, longitude)
        } else {
            realismLayer.truncateIfNeeded(latitude, longitude)
        }

        val finalAltitude = realismLayer.generateAltitude(altitude)
        val horizontalAccuracy = realismLayer.generateHorizontalAccuracy()
        val verticalAccuracy = realismLayer.generateVerticalAccuracy()
        val speedAccuracy = realismLayer.generateSpeedAccuracy()
        val bearingAccuracy = realismLayer.generateBearingAccuracy()

        var lastLocation: Location? = null
        val providers = getActiveProviders()

        for (provider in providers) {
            try {
                val location = Location(provider).apply {
                    this.latitude = finalLat
                    this.longitude = finalLon
                    this.altitude = finalAltitude
                    this.speed = speed
                    this.bearing = bearing
                    this.accuracy = horizontalAccuracy
                    this.time = System.currentTimeMillis()
                    this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        this.bearingAccuracyDegrees = bearingAccuracy
                        this.speedAccuracyMetersPerSecond = speedAccuracy
                        this.verticalAccuracyMeters = verticalAccuracy
                    }
                }

                locationManager.setTestProviderLocation(provider, location)
                lastLocation = location
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException setting mock location for $provider: permission revoked", e)
                isInitialized = false
                return Result.failure(MockLocationError.NotSelectedAsMockApp(cause = e))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set location for provider $provider: ${e.message}")
            }
        }

        return if (lastLocation != null) {
            Result.success(lastLocation)
        } else {
            Result.failure(MockLocationError.ProviderUnavailable("all", "No providers accepted test location"))
        }
    }

    /**
     * Tears down test providers, disabling them and returning the device to real location hardware.
     */
    @Synchronized
    fun stop() {
        if (!isInitialized) return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, FUSED_PROVIDER_NAME)
        for (provider in providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up test provider $provider: ${e.message}")
            }
        }
        isInitialized = false
    }

    fun isEngineActive(): Boolean = isInitialized
}
