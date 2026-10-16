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
import com.fakegps.mocklocation.util.PermissionHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface IGmsLocationClient {
    fun setMockMode(isMockMode: Boolean): Task<Void>
    fun setMockLocation(mockLocation: Location): Task<Void>
}

class DefaultGmsLocationClient(
    private val client: FusedLocationProviderClient
) : IGmsLocationClient {
    constructor(context: Context) : this(LocationServices.getFusedLocationProviderClient(context.applicationContext))
    override fun setMockMode(isMockMode: Boolean): Task<Void> = client.setMockMode(isMockMode)
    override fun setMockLocation(mockLocation: Location): Task<Void> = client.setMockLocation(mockLocation)
}

/**
 * Low-level mock location provider engine interfacing directly with Android's LocationManager
 * and Google Play Services FusedLocationProviderClient.
 * Injects mock coordinates directly into GPS_PROVIDER, NETWORK_PROVIDER, and Google Play FUSED_PROVIDER.
 * Ensures Google Maps, Google Play Services, WhatsApp, and games receive consistent, rock-solid, uninterrupted spoofed coordinates
 * with zero rubberbanding or glitching back to real location.
 */
class MockLocationEngine(
    private val context: Context,
    private val settingsPrefs: AppSettingsPreferences = AppSettingsPreferences(context),
    private val realismLayer: RealismLayer = RealismLayer(settingsPrefs),
    val ghostCloakEngine: GhostCloakEngine = GhostCloakEngine(settingsPrefs),
    private val gmsLocationClient: IGmsLocationClient = DefaultGmsLocationClient(context)
) {
    companion object {
        private const val TAG = "MockLocationEngine"
        const val FUSED_PROVIDER_NAME = "fused"
    }

    enum class GmsState {
        OFF,
        ENGAGING,
        ENGAGED
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var isInitialized = false

    @Volatile
    var gmsState: GmsState = GmsState.OFF
        private set

    val isGmsMockModeEngaged: Boolean
        get() = gmsState == GmsState.ENGAGED

    private val reengageInFlight = AtomicBoolean(false)
    var consecutiveFailures = 0
        internal set
    var nextRetryAtElapsedMs = 0L
        internal set

    private val registeredProviders = mutableSetOf<String>()

    // Monotonic clock tracking
    private var lastInjectedNanos = 0L

    // Diagnostics counters (10s window)
    private var windowStartMs = SystemClock.elapsedRealtime()
    private var lastInjectionElapsedMs = 0L
    private var windowInjections = 0
    private var windowGmsFailures = 0
    private var windowReengageAttempts = 0
    private var windowMaxGapMs = 0L
    private var totalReengageAttempts = 0

    private fun getTargetProviders(): List<String> {
        // Strictly register GPS and NETWORK with system LocationManager.
        // FUSED is handled directly via Google Play Services FusedLocationProviderClient because
        // calling addTestProvider("fused") throws IllegalArgumentException on Android 12+ (API 31+).
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }

    /**
     * Non-blocking, single-flight re-engagement of GMS Fused Location Provider mock mode with exponential backoff.
     */
    fun requestGmsReengage() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextRetryAtElapsedMs) {
            return
        }
        if (!reengageInFlight.compareAndSet(false, true)) {
            return
        }

        gmsState = GmsState.ENGAGING
        totalReengageAttempts++
        windowReengageAttempts++

        try {
            gmsLocationClient.setMockMode(true)
                .addOnSuccessListener {
                    gmsState = GmsState.ENGAGED
                    consecutiveFailures = 0
                    nextRetryAtElapsedMs = 0L
                    reengageInFlight.set(false)
                    Log.i(TAG, "GMS mock mode successfully engaged.")
                }
                .addOnFailureListener { e ->
                    gmsState = GmsState.OFF
                    consecutiveFailures++
                    val shift = (consecutiveFailures - 1).coerceIn(0, 3)
                    val backoffMs = minOf(250L * (1L shl shift), 1500L)
                    nextRetryAtElapsedMs = SystemClock.elapsedRealtime() + backoffMs
                    reengageInFlight.set(false)
                    Log.w(TAG, "GMS setMockMode failed (failures=$consecutiveFailures, retry in ${backoffMs}ms): ${e.message}")
                }
        } catch (e: Exception) {
            gmsState = GmsState.OFF
            consecutiveFailures++
            val shift = (consecutiveFailures - 1).coerceIn(0, 3)
            val backoffMs = minOf(250L * (1L shl shift), 1500L)
            nextRetryAtElapsedMs = SystemClock.elapsedRealtime() + backoffMs
            reengageInFlight.set(false)
            Log.w(TAG, "Exception launching GMS setMockMode: ${e.message}")
        }
    }

    /**
     * Backward-compatible helper to check or engage GMS mock mode.
     */
    fun ensureGmsMockMode(timeoutMs: Long = 3000L): Boolean {
        if (gmsState == GmsState.ENGAGED) return true
        return try {
            val task = gmsLocationClient.setMockMode(true)
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS)
                gmsState = GmsState.ENGAGED
                consecutiveFailures = 0
                nextRetryAtElapsedMs = 0L
                Log.d(TAG, "GMS FusedLocationProviderClient mock mode successfully engaged (synchronous).")
                true
            } else {
                requestGmsReengage()
                gmsState == GmsState.ENGAGED
            }
        } catch (e: Exception) {
            requestGmsReengage()
            false
        }
    }

    /**
     * Initializes all test providers with fail-safe recovery, GMS Fused mock mode, and root auto-grant attempt.
     */
    @Synchronized
    fun initialize(): Result<Unit> {
        if (!PermissionHelper.isMockLocationEnabled(context) && PermissionHelper.isDeviceRooted()) {
            Log.d(TAG, "Device rooted: attempting automated root mock permission grant...")
            PermissionHelper.tryAutoGrantRootMockPermission(context)
        }

        // Suppress background Wi-Fi and Bluetooth scanning if rooted to prevent rubberbanding
        if (PermissionHelper.isDeviceRooted()) {
            PermissionHelper.disableScanningIfRooted(context)
        }

        // Engage Google Play Services Fused Location Provider Mock Mode once
        try {
            val task = gmsLocationClient.setMockMode(true)
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                Tasks.await(task, 3000L, TimeUnit.MILLISECONDS)
                gmsState = GmsState.ENGAGED
                consecutiveFailures = 0
                nextRetryAtElapsedMs = 0L
                Log.i(TAG, "GMS FusedLocationProviderClient mock mode successfully engaged.")
            } else {
                if (task.isComplete) {
                    if (task.isSuccessful) {
                        gmsState = GmsState.ENGAGED
                        consecutiveFailures = 0
                        nextRetryAtElapsedMs = 0L
                    } else {
                        gmsState = GmsState.OFF
                        consecutiveFailures = 1
                        nextRetryAtElapsedMs = SystemClock.elapsedRealtime() + 1000L
                    }
                } else {
                    task.addOnSuccessListener {
                        gmsState = GmsState.ENGAGED
                        consecutiveFailures = 0
                        nextRetryAtElapsedMs = 0L
                    }.addOnFailureListener {
                        gmsState = GmsState.OFF
                        consecutiveFailures = 1
                        nextRetryAtElapsedMs = SystemClock.elapsedRealtime() + 1000L
                    }
                }
            }
        } catch (e: Exception) {
            gmsState = GmsState.OFF
            consecutiveFailures = 1
            nextRetryAtElapsedMs = SystemClock.elapsedRealtime() + 1000L
            Log.w(TAG, "Failed to engage GMS mock mode during initialize(): ${e.message}")
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
                Log.w(TAG, "Error registering test provider $provider: ${e.message}")
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

    private fun recordSuccessfulInjection(nowRealtime: Long) {
        if (lastInjectionElapsedMs > 0L) {
            val gap = nowRealtime - lastInjectionElapsedMs
            if (gap > windowMaxGapMs) {
                windowMaxGapMs = gap
            }
            if (gap > 1500L) {
                Log.w(TAG, "Injection gap warning: ${gap}ms exceeded 1500ms threshold!")
            }
        }
        lastInjectionElapsedMs = nowRealtime
        windowInjections++

        val elapsedWindow = nowRealtime - windowStartMs
        if (elapsedWindow >= 10_000L) {
            val seconds = elapsedWindow / 1000.0
            val rate = if (seconds > 0) windowInjections / seconds else 0.0
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Health (10s): %.2f inj/sec, %d GMS failures, %d re-engages, state=%s, max gap=%d ms",
                    rate,
                    windowGmsFailures,
                    windowReengageAttempts,
                    gmsState.name,
                    windowMaxGapMs
                )
            )
            windowStartMs = nowRealtime
            windowInjections = 0
            windowGmsFailures = 0
            windowReengageAttempts = 0
            windowMaxGapMs = 0L
        }
    }

    /**
     * Injects a spoofed coordinate into the OS location system for all registered providers simultaneously.
     * Guarantees rock-solid stability with zero fluctuation when jitter is disabled.
     * Non-blocking for GMS injection: never delays the hardware tick cadence.
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
        val horizontalAccuracy = if (isMoving) realismLayer.generateHorizontalAccuracy(true) else 3.5f
        val verticalAccuracy = if (isMoving) realismLayer.generateVerticalAccuracy(true) else 1.5f
        val speedAccuracy = if (isMoving) realismLayer.generateSpeedAccuracy(true) else 0.1f
        val bearingAccuracy = if (isMoving) realismLayer.generateBearingAccuracy(true) else 1.0f

        var lastSuccessfulLocation: Location? = null
        val nowMs = System.currentTimeMillis()
        val currentNanos = SystemClock.elapsedRealtimeNanos()
        val nowNanos = if (currentNanos <= lastInjectedNanos) {
            lastInjectedNanos + 1_000L // Strictly monotonic increasing
        } else {
            currentNanos
        }
        lastInjectedNanos = nowNanos

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        this.mslAltitudeMeters = finalAltitude
                        this.mslAltitudeAccuracyMeters = verticalAccuracy
                    }
                }

                // Apply Ghost Cloak Anti-Detection Transformation (NMEA, Micro-Clock Drift, Satellite Constellation)
                val cloakedLocation = ghostCloakEngine.cloakLocation(rawLocation, nowMs, nowNanos)

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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            this.bearingAccuracyDegrees = bearingAccuracy
                            this.speedAccuracyMetersPerSecond = speedAccuracy
                            this.verticalAccuracyMeters = verticalAccuracy
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            this.mslAltitudeMeters = finalAltitude
                            this.mslAltitudeAccuracyMeters = verticalAccuracy
                        }
                    }
                    val cloakedRetry = ghostCloakEngine.cloakLocation(retryLoc, nowMs, nowNanos)
                    locationManager.setTestProviderLocation(provider, cloakedRetry)
                    lastSuccessfulLocation = cloakedRetry
                } catch (ignored: Exception) {}
            }
        }

        if (lastSuccessfulLocation != null) {
            val nowRealtime = SystemClock.elapsedRealtime()
            recordSuccessfulInjection(nowRealtime)

            // Inject directly into Google Play Services Fused Location Provider Client.
            // Feeds authoritative high-accuracy GPS fixes with full GNSS satellite metadata.
            // Non-blocking: attaches listeners, never halts tick cadence.
            if (gmsState == GmsState.ENGAGED) {
                try {
                    val gmsGpsFix = Location(LocationManager.GPS_PROVIDER).apply {
                        this.latitude = finalLat
                        this.longitude = finalLon
                        this.altitude = finalAltitude
                        this.speed = speed
                        this.bearing = bearing
                        this.accuracy = horizontalAccuracy.coerceIn(2.0f, 3.2f)
                        this.time = nowMs
                        this.elapsedRealtimeNanos = nowNanos

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            this.bearingAccuracyDegrees = bearingAccuracy
                            this.speedAccuracyMetersPerSecond = speedAccuracy
                            this.verticalAccuracyMeters = verticalAccuracy
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            this.mslAltitudeMeters = finalAltitude
                            this.mslAltitudeAccuracyMeters = verticalAccuracy
                        }
                    }

                    // Apply Ghost Cloak GNSS satellite bundle (PRNs, SNR/cn0, NMEA, clock sync)
                    val cloakedGmsFix = ghostCloakEngine.cloakLocation(gmsGpsFix, nowMs, nowNanos)

                    gmsLocationClient.setMockLocation(cloakedGmsFix)
                        .addOnSuccessListener {
                            consecutiveFailures = 0
                        }
                        .addOnFailureListener { e ->
                            consecutiveFailures++
                            windowGmsFailures++
                            Log.w(TAG, "GMS setMockLocation (gps) failed (failures=$consecutiveFailures): ${e.message}")
                            requestGmsReengage()
                        }

                    // Dual injection: Also feed "fused" provider fix so Google Maps' Kalman filter receives seamless continuous updates
                    val gmsFusedFix = Location(FUSED_PROVIDER_NAME).apply {
                        this.latitude = finalLat
                        this.longitude = finalLon
                        this.altitude = finalAltitude
                        this.speed = speed
                        this.bearing = bearing
                        this.accuracy = horizontalAccuracy.coerceIn(2.0f, 3.2f)
                        this.time = nowMs
                        this.elapsedRealtimeNanos = nowNanos

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            this.bearingAccuracyDegrees = bearingAccuracy
                            this.speedAccuracyMetersPerSecond = speedAccuracy
                            this.verticalAccuracyMeters = verticalAccuracy
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            this.mslAltitudeMeters = finalAltitude
                            this.mslAltitudeAccuracyMeters = verticalAccuracy
                        }
                    }
                    val cloakedFusedFix = ghostCloakEngine.cloakLocation(gmsFusedFix, nowMs, nowNanos)
                    gmsLocationClient.setMockLocation(cloakedFusedFix)
                } catch (t: Throwable) {
                    consecutiveFailures++
                    windowGmsFailures++
                    Log.w(TAG, "GMS mock injection exception: ${t.message}")
                    requestGmsReengage()
                }
            } else {
                requestGmsReengage()
            }

            return Result.success(lastSuccessfulLocation)
        } else {
            isInitialized = false
            return Result.failure(MockLocationError.ProviderUnavailable("all", "No test provider accepted mock coordinate"))
        }
    }

    /**
     * Tears down test providers cleanly and disengages GMS mock mode.
     */
    @Synchronized
    fun stop() {
        try {
            gmsLocationClient.setMockMode(false)
        } catch (ignored: Exception) {}
        gmsState = GmsState.OFF
        reengageInFlight.set(false)
        consecutiveFailures = 0
        nextRetryAtElapsedMs = 0L
        lastInjectedNanos = 0L
        lastInjectionElapsedMs = 0L

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
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
