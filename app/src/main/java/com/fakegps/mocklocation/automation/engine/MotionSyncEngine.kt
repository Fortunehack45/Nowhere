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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        const val STEP_MOTION_TIMEOUT_MS = 2000L // 2s without steps means walking has stopped
        const val VEHICLE_VIBRATION_TIMEOUT_MS = 1500L // 1.5s silence means vehicle transit has stopped
        const val VEHICLE_MIN_VARIANCE = 8.5f // Continuous vehicle engine/road vibration
        const val STATIONARY_MAX_VARIANCE = 2.0f // Stationary handheld or table threshold
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
    private var magneticSensor: Sensor? = null

    private val lastAccelValues = FloatArray(3)
    private val lastMagValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    // Simulation runtime state
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentHeading: Float = 0.0f
    private var lastMotionTimestamp: Long = 0L
    private var lastStepTimestamp: Long = 0L
    private var lastVehicleVibrationTimestamp: Long = 0L
    private var lastReportedSpeed: Float = 0f

    // Step counter fallback tracking
    private var initialStepCount = -1f
    private var lastStepCount = -1f

    // Accelerometer rolling variance buffer
    private val accelWindow = FloatArray(30)
    private var accelIndex = 0
    private var accelCount = 0
    private var accelMean = 9.81f

    // Step processing serialization mutex to prevent race conditions during rapid movement
    private val stepMutex = Mutex()

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
        lastStepTimestamp = 0L
        lastVehicleVibrationTimestamp = 0L
        lastReportedSpeed = 0f
        initialStepCount = -1f
        lastStepCount = -1f
        hasAccel = false
        hasMag = false

        if (sensorManager != null) {
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            if (stepDetectorSensor != null) {
                sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (stepCounterSensor != null) {
                sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }

            if (rotationVectorSensor != null) {
                sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (magneticSensor != null) {
                sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME)
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
        lastReportedSpeed = 0f
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

                System.arraycopy(event.values, 0, lastAccelValues, 0, 3)
                hasAccel = true
                if (rotationVectorSensor == null && hasMag) {
                    updateOrientationFromAccelMag()
                }

                // Push to rolling variance window
                accelWindow[accelIndex] = magnitude
                accelIndex = (accelIndex + 1) % accelWindow.size
                if (accelCount < accelWindow.size) accelCount++

                checkAccelerometerMotionState()

                // Pedometer peak detection fallback when hardware step sensor chip is unavailable
                if (stepDetectorSensor == null && stepCounterSensor == null) {
                    val now = System.currentTimeMillis()
                    val threshold = (accelMean + 1.8f).coerceIn(11.5f, 13.5f)
                    if (magnitude > threshold && (now - lastStepTimestamp > 330L)) {
                        onPhysicalStepDetected()
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagValues, 0, 3)
                hasMag = true
                if (rotationVectorSensor == null && hasAccel) {
                    updateOrientationFromAccelMag()
                }
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

                // Smooth heading update using angular normalization to eliminate boundary jump jitter
                val delta = TerrainLockEngine.normalizeDeltaDegrees(azimuthDeg - currentHeading)
                currentHeading = (currentHeading + delta * 0.35f + 360f) % 360f
            }
        }
    }

    private fun updateOrientationFromAccelMag() {
        val rMatrix = FloatArray(9)
        val iMatrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(rMatrix, iMatrix, lastAccelValues, lastMagValues)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rMatrix, orientation)
            var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (azimuthDeg < 0f) azimuthDeg += 360f
            val delta = TerrainLockEngine.normalizeDeltaDegrees(azimuthDeg - currentHeading)
            currentHeading = (currentHeading + delta * 0.35f + 360f) % 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onPhysicalStepDetected() {
        val now = System.currentTimeMillis()
        val deltaT = if (lastStepTimestamp > 0L) (now - lastStepTimestamp) / 1000.0 else 0.6
        lastStepTimestamp = now
        lastMotionTimestamp = now
        isVehicleMode.set(false)

        // Stride with ±10% jitter
        val jitter = (Random.nextDouble(-STRIDE_JITTER_RATIO, STRIDE_JITTER_RATIO)) * DEFAULT_STRIDE_LENGTH_METERS
        val stepDistance = DEFAULT_STRIDE_LENGTH_METERS + jitter

        val calculatedSpeedKmh = if (deltaT in 0.3..2.0) {
            ((stepDistance / deltaT) * 3.6).toFloat().coerceIn(2.8f, 7.5f)
        } else {
            4.5f
        }

        advanceLocation(stepDistance, calculatedSpeedKmh)
    }

    private fun checkAccelerometerMotionState() {
        if (accelCount < 10) return

        var sum = 0f
        for (i in 0 until accelCount) sum += accelWindow[i]
        val mean = sum / accelCount
        accelMean = mean

        var varianceSum = 0f
        for (i in 0 until accelCount) {
            val diff = accelWindow[i] - mean
            varianceSum += diff * diff
        }
        val variance = varianceSum / accelCount

        val now = System.currentTimeMillis()
        val timeSinceStep = now - lastStepTimestamp

        if (variance > VEHICLE_MIN_VARIANCE) {
            lastVehicleVibrationTimestamp = now
            // Sustained road vibration without foot steps indicates vehicle transit
            if (timeSinceStep > 2500L) {
                isVehicleMode.set(true)
                lastMotionTimestamp = now
            }
        } else if (variance < STATIONARY_MAX_VARIANCE) {
            // Calm accelerometer readings -> immediately disengage vehicle transit
            isVehicleMode.set(false)
        }
    }

    private fun startVehicleDetectionLoop() {
        vehicleTickJob?.cancel()
        vehicleTickJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val timeSinceStep = now - lastStepTimestamp
                val timeSinceVibration = now - lastVehicleVibrationTimestamp

                val isActivelyInVehicle = isVehicleMode.get() && (timeSinceVibration < VEHICLE_VIBRATION_TIMEOUT_MS)
                if (!isActivelyInVehicle) {
                    isVehicleMode.set(false)
                }

                val isActivelyWalking = timeSinceStep < STEP_MOTION_TIMEOUT_MS
                val isMoving = isActivelyWalking || isActivelyInVehicle

                if (isActivelyInVehicle) {
                    lastMotionTimestamp = now
                    // In vehicle mode, advance ~4.16 meters per 500ms (~30 km/h)
                    val vehicleDistance = (VEHICLE_MAX_SPEED_KMH * 1000.0 / 3600.0) * 0.5
                    advanceLocation(vehicleDistance, VEHICLE_MAX_SPEED_KMH)
                } else if (!isMoving) {
                    // USER IS STATIONARY: Strictly hold position and notify speed = 0.0 km/h with 0 drift
                    if (lastReportedSpeed > 0f) {
                        lastReportedSpeed = 0f
                        withContext(Dispatchers.Main) {
                            onLocationUpdated(currentLat, currentLon, currentHeading, 0f)
                        }
                    }
                }
            }
        }
    }

    private fun advanceLocation(distanceMeters: Double, speedKmh: Float) {
        scope.launch {
            stepMutex.withLock {
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
                            return@withLock
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
                lastReportedSpeed = speedKmh

                withContext(Dispatchers.Main) {
                    onLocationUpdated(currentLat, currentLon, currentHeading, speedKmh)
                }
            }
        }
    }
}
