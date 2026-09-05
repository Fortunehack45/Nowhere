package com.fakegps.mocklocation.automation.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.fakegps.mocklocation.automation.data.AutomationLogEntity
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.engine.GeoUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MotionSyncEngine: Standalone sensor-driven motion simulation.
 *
 * NOTE ON SCOPE & PRIVACY:
 * This component reads device physical motion sensors ONLY (accelerometer, step detector, rotation vector).
 * It NEVER queries Android LocationManager, GPS, or fused location fixes. The device's real geographical
 * position is never accessed or inspected.
 */
class MotionSyncEngine(
    private val context: Context,
    private val onLocationUpdated: (latitude: Double, longitude: Double, bearing: Float, speedKmh: Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "MotionSyncEngine"
        const val DEFAULT_STRIDE_LENGTH_METERS = 0.75
        const val STRIDE_JITTER_RATIO = 0.10 // ±10%
        const val IDLE_THRESHOLD_MS = 5000L // 5s idle hold
        const val VEHICLE_ACCEL_VARIANCE_THRESHOLD = 7.5f // m²/s⁴
        const val VEHICLE_MAX_SPEED_KMH = 30.0f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val isRunning = AtomicBoolean(false)
    private var isRoutePlaybackActive = false

    // Hardware sensors
    private var stepDetectorSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    // Simulation runtime state
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentHeading: Float = 0.0f
    private var lastMotionTimestamp: Long = 0L

    // Step counter fallback tracking
    private var initialStepCount = -1f
    private var lastStepCount = -1f

    // Accelerometer rolling variance buffer
    private val accelWindow = FloatArray(30)
    private var accelIndex = 0
    private var accelCount = 0

    // Vehicle mode vehicle ticker job
    private var vehicleTickJob: Job? = null
    private val isVehicleMode = AtomicBoolean(false)

    fun setRoutePlaybackActive(active: Boolean) {
        isRoutePlaybackActive = active
        if (active && isRunning.get()) {
            stop()
            Log.d(TAG, "MotionSync paused due to active route playback (mutually exclusive).")
        }
    }

    fun isRoutePlaybackActive(): Boolean = isRoutePlaybackActive

    fun setInitialCoordinate(lat: Double, lon: Double, headingDeg: Float) {
        currentLat = lat
        currentLon = lon
        currentHeading = headingDeg
    }

    fun start(initialLat: Double, initialLon: Double, initialHeading: Float = 0.0f) {
        if (isRoutePlaybackActive) {
            Log.w(TAG, "Cannot start MotionSync: Route playback is actively running.")
            return
        }

        if (isRunning.getAndSet(true)) return

        currentLat = initialLat
        currentLon = initialLon
        currentHeading = initialHeading
        lastMotionTimestamp = System.currentTimeMillis()
        initialStepCount = -1f
        lastStepCount = -1f

        if (sensorManager != null) {
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            if (stepDetectorSensor != null) {
                sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (stepCounterSensor != null) {
                sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }

            rotationVectorSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        startVehicleDetectionLoop()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        sensorManager?.unregisterListener(this)
        vehicleTickJob?.cancel()
        vehicleTickJob = null
        isVehicleMode.set(false)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning.get() || isRoutePlaybackActive || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                onPhysicalStepDetected()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0]
                if (initialStepCount < 0) {
                    initialStepCount = totalSteps
                    lastStepCount = totalSteps
                } else {
                    val delta = totalSteps - lastStepCount
                    if (delta >= 1f) {
                        lastStepCount = totalSteps
                        onPhysicalStepDetected()
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)

                // Push to rolling variance window
                accelWindow[accelIndex] = magnitude
                accelIndex = (accelIndex + 1) % accelWindow.size
                if (accelCount < accelWindow.size) accelCount++

                checkAccelerometerMotionState()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientationValues = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)

                // Azimuth in degrees [0, 360)
                val azimuthRad = orientationValues[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0f) azimuthDeg += 360f
                currentHeading = azimuthDeg
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onPhysicalStepDetected() {
        lastMotionTimestamp = System.currentTimeMillis()
        isVehicleMode.set(false)

        // Stride with ±10% jitter
        val jitter = (Random.nextDouble(-STRIDE_JITTER_RATIO, STRIDE_JITTER_RATIO)) * DEFAULT_STRIDE_LENGTH_METERS
        val stepDistance = DEFAULT_STRIDE_LENGTH_METERS + jitter

        advanceLocation(stepDistance, 4.5f) // ~4.5 km/h walking speed
    }

    private fun checkAccelerometerMotionState() {
        if (accelCount < 10) return

        var sum = 0f
        for (i in 0 until accelCount) sum += accelWindow[i]
        val mean = sum / accelCount

        var varianceSum = 0f
        for (i in 0 until accelCount) {
            val diff = accelWindow[i] - mean
            varianceSum += diff * diff
        }
        val variance = varianceSum / accelCount

        val timeSinceStep = System.currentTimeMillis() - lastMotionTimestamp

        // High intensity variance without steps indicates vehicle transit
        if (variance > VEHICLE_ACCEL_VARIANCE_THRESHOLD && timeSinceStep > 3000L) {
            if (!isVehicleMode.get()) {
                isVehicleMode.set(true)
                lastMotionTimestamp = System.currentTimeMillis()
            }
        }
    }

    private fun startVehicleDetectionLoop() {
        vehicleTickJob?.cancel()
        vehicleTickJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(1000L)
                val now = System.currentTimeMillis()
                if (isVehicleMode.get()) {
                    lastMotionTimestamp = now
                    // In vehicle mode, advance ~8.3 meters per second (~30 km/h)
                    val vehicleDistance = (VEHICLE_MAX_SPEED_KMH * 1000.0 / 3600.0)
                    advanceLocation(vehicleDistance, VEHICLE_MAX_SPEED_KMH)
                } else {
                    // Check idle hold (5s silence)
                    if (now - lastMotionTimestamp > IDLE_THRESHOLD_MS) {
                        // Hold position with 0 drift
                    }
                }
            }
        }
    }

    private fun advanceLocation(distanceMeters: Double, speedKmh: Float) {
        scope.launch {
            val db = AppDatabase.getInstance(context)
            val settings = db.automationSettingsDao().getSettings()

            val terrainLockEnabled = settings?.terrainLockEnabled ?: true
            var nextLat = currentLat
            var nextLon = currentLon
            var nextHeading = currentHeading

            if (terrainLockEnabled) {
                val stepResult = TerrainLockEngine.evaluateStep(
                    context = context,
                    currentLat = currentLat,
                    currentLon = currentLon,
                    currentHeading = currentHeading,
                    stepDistanceMeters = distanceMeters,
                    checkRestricted = settings?.terrainRestrictedEnabled ?: false,
                    searchRadiusMeters = (settings?.terrainSearchRadiusMeters ?: 25f).toDouble(),
                    allowUnmapped = settings?.terrainAllowUnmapped ?: true
                )

                when (stepResult) {
                    is TerrainLockEngine.TerrainStepResult.Accepted -> {
                        nextLat = stepResult.lat
                        nextLon = stepResult.lon
                        nextHeading = stepResult.bearing
                    }
                    is TerrainLockEngine.TerrainStepResult.Deflected -> {
                        nextLat = stepResult.lat
                        nextLon = stepResult.lon
                        nextHeading = stepResult.bearing
                    }
                    is TerrainLockEngine.TerrainStepResult.Steered -> {
                        nextLat = stepResult.lat
                        nextLon = stepResult.lon
                        nextHeading = stepResult.bearing
                    }
                    is TerrainLockEngine.TerrainStepResult.HoldPosition -> {
                        // Hold position exactly. Log TERRAIN_BLOCKED event
                        db.automationLogDao().logEvent(
                            AutomationLogEntity(
                                source = "TERRAIN",
                                targetSummary = "Hold Position ($currentLat, $currentLon)",
                                details = stepResult.reason
                            )
                        )
                        return@launch
                    }
                }
            } else {
                // Raw motion sync without terrain check
                val (destLat, destLon) = GeoUtils.computeDestinationPoint(currentLat, currentLon, currentHeading, distanceMeters)
                nextLat = destLat
                nextLon = destLon
            }

            currentLat = nextLat
            currentLon = nextLon
            currentHeading = nextHeading

            withContext(Dispatchers.Main) {
                onLocationUpdated(currentLat, currentLon, currentHeading, speedKmh)
            }
        }
    }
}
