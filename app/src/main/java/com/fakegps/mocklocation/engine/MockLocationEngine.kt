package com.fakegps.mocklocation.engine

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.util.PermissionHelper

/**
 * Low-level mock location provider engine interfacing directly with Android's LocationManager.
 * Injects mock coordinates directly into GPS_PROVIDER, NETWORK_PROVIDER, PASSIVE_PROVIDER, and Google Play FUSED_PROVIDER.
 * Ensures Google Maps, Google Play Services, WhatsApp, and games receive consistent, rock-solid, uninterrupted spoofed coordinates.
 */
class MockLocationEngine(
    private val context: Context,
    private val realismLayer: RealismLayer = RealismLayer(),
    private val settingsPrefs: AppSettingsPreferences = AppSettingsPreferences(context),
    val ghostCloakEngine: GhostCloakEngine = GhostCloakEngine(settingsPrefs)
) {
    companion object {
        private const val TAG = "MockLocationEngine"
        const val FUSED_PROVIDER_NAME = "fused"
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var isInitialized = false

    private val registeredProviders = mutableSetOf<String>()

    private fun getTargetProviders(): List<String> {
        val list = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        if (settingsPrefs.useFusedProvider) {
            list.add(FUSED_PROVIDER_NAME)
        }
        return list
    }

    /**
     * Initializes all test providers with fail-safe recovery and root auto-grant attempt.
     */
    @Synchronized
    fun initialize(): Result<Unit> {
        if (!PermissionHelper.isMockLocationEnabled(context) && PermissionHelper.isDeviceRooted()) {
            Log.d(TAG, "Device rooted: attempting automated root mock permission grant...")
            PermissionHelper.tryAutoGrantRootMockPermission(context)
        }

        val providers = getTargetProviders()
        var atLeastOneRegistered = false
        var lastSecurityException: SecurityException? = null

        for (provider in providers) {
            try {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (ignored: Exception) {}

                registerTestProvider(provider)
                registeredProviders.add(provider)
                atLeastOneRegistered = true
                Log.d(TAG, "Successfully registered test provider: $provider")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering $provider: App not selected as Mock App in Developer Options", e)
                lastSecurityException = e
            } catch (e: Exception) {
                Log.w(TAG, "Non-fatal error registering test provider $provider: ${e.message}")
                try {
                    locationManager.setTestProviderEnabled(provider, true)
                    registeredProviders.add(provider)
                    atLeastOneRegistered = true
                } catch (ignored: Exception) {}
            }
        }

        return if (atLeastOneRegistered) {
            isInitialized = true
            Result.success(Unit)
        } else {
            isInitialized = false
            Result.failure(
                lastSecurityException?.let { MockLocationError.NotSelectedAsMockApp(cause = it) }
                    ?: MockLocationError.ProviderUnavailable("all", "Could not register any test provider")
            )
        }
    }

    private fun registerTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setHasNetworkRequirement(provider == LocationManager.NETWORK_PROVIDER || provider == FUSED_PROVIDER_NAME)
                .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER || provider == FUSED_PROVIDER_NAME)
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
                provider == LocationManager.NETWORK_PROVIDER || provider == FUSED_PROVIDER_NAME,
                provider == LocationManager.GPS_PROVIDER || provider == FUSED_PROVIDER_NAME,
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
        try {
            @Suppress("DEPRECATION")
            locationManager.setTestProviderStatus(
                provider,
                android.location.LocationProvider.AVAILABLE,
                null,
                System.currentTimeMillis()
            )
        } catch (ignored: Exception) {}
    }

    /**
     * Injects a spoofed coordinate into the OS location system for all registered providers simultaneously.
     * Guarantees rock-solid stability with zero fluctuation when jitter is disabled.
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
        if (!isInitialized || registeredProviders.isEmpty()) {
            val initRes = initialize()
            if (initRes.isFailure) return Result.failure(initRes.exceptionOrNull() ?: MockLocationError.InternalError("Init failed"))
        }

        val isMoving = speed >= 0.1f
        // Rock-solid fixed coordinates when stationary unless jitter is explicitly turned on
        val (finalLat, finalLon) = if (applyStationaryJitter && !isMoving) {
            realismLayer.applyJitter(latitude, longitude)
        } else {
            Pair(latitude, longitude)
        }

        val finalAltitude = if (isMoving) realismLayer.generateAltitude(altitude, true) else altitude
        val horizontalAccuracy = if (isMoving) realismLayer.generateHorizontalAccuracy(true) else 1.0f
        val verticalAccuracy = if (isMoving) realismLayer.generateVerticalAccuracy(true) else 0.5f
        val speedAccuracy = if (isMoving) realismLayer.generateSpeedAccuracy(true) else 0.0f
        val bearingAccuracy = if (isMoving) realismLayer.generateBearingAccuracy(true) else 0.0f

        var lastSuccessfulLocation: Location? = null
        val nowMs = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()

        val providersToUse = if (registeredProviders.isNotEmpty()) registeredProviders.toList() else getTargetProviders()

        for (provider in providersToUse) {
            try {
                val rawLocation = Location(provider).apply {
                    this.latitude = finalLat
                    this.longitude = finalLon
                    this.altitude = finalAltitude
                    this.speed = speed
                    this.bearing = bearing
                    this.accuracy = horizontalAccuracy
                    this.time = nowMs
                    this.elapsedRealtimeNanos = nowNanos

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        this.bearingAccuracyDegrees = bearingAccuracy
                        this.speedAccuracyMetersPerSecond = speedAccuracy
                        this.verticalAccuracyMeters = verticalAccuracy
                    }
                }

                // Apply Ghost Cloak Anti-Detection Transformation (NMEA, Micro-Clock Drift, Satellite Constellation)
                val cloakedLocation = ghostCloakEngine.cloakLocation(rawLocation, nowMs, nowNanos)

                locationManager.setTestProviderEnabled(provider, true)
                locationManager.setTestProviderLocation(provider, cloakedLocation)
                lastSuccessfulLocation = cloakedLocation
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException setting mock location for $provider: permission revoked", e)
                isInitialized = false
                return Result.failure(MockLocationError.NotSelectedAsMockApp(cause = e))
            } catch (e: Exception) {
                Log.w(TAG, "Non-fatal error injecting location into $provider: ${e.message}, attempting auto-recovery...")
                try {
                    locationManager.setTestProviderEnabled(provider, true)
                    val retryLoc = Location(provider).apply {
                        this.latitude = finalLat
                        this.longitude = finalLon
                        this.altitude = finalAltitude
                        this.speed = speed
                        this.bearing = bearing
                        this.accuracy = horizontalAccuracy
                        this.time = nowMs
                        this.elapsedRealtimeNanos = nowNanos
                    }
                    val cloakedRetry = ghostCloakEngine.cloakLocation(retryLoc, nowMs, nowNanos)
                    locationManager.setTestProviderLocation(provider, cloakedRetry)
                    lastSuccessfulLocation = cloakedRetry
                } catch (ignored: Exception) {}
            }
        }

        return if (lastSuccessfulLocation != null) {
            Result.success(lastSuccessfulLocation)
        } else {
            isInitialized = false
            Result.failure(MockLocationError.ProviderUnavailable("all", "No test provider accepted mock coordinate"))
        }
    }

    /**
     * Tears down test providers cleanly.
     */
    @Synchronized
    fun stop() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, FUSED_PROVIDER_NAME)
        for (provider in providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (ignored: Exception) {}
        }
        registeredProviders.clear()
        isInitialized = false
    }

    fun isEngineActive(): Boolean = isInitialized
}
