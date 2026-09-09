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
 * Sensor-driven mock movement. Reads accelerometer / step / rotation only — never real GPS.
 * Holds position when the user is stationary and pulses the last fix so apps do not rubber-band.
 */
class MotionSyncEngine(
    private val context: Context,
    private val onLocationUpdated: (latitude: Double, longitude: Double, bearing: Float, speedKmh: Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "MotionSyncEngine"
        const val DEFAULT_STRIDE_LENGTH_METERS = 0.75
        const val STRIDE_JITTER_RATIO = 0.10
        const val STEP_MOTION_TIMEOUT_MS = 1800L
        const val VEHICLE_VIBRATION_TIMEOUT_MS = 1600L
        const val VEHICLE_MIN_VARIANCE = 14.0f
        const val STATIONARY_MAX_VARIANCE = 1.6f
        const val VEHICLE_MAX_SPEED_KMH = 30.0f
        const val WALK_SPEED_KMH = 4.6f
        const val STEP_PEAK_THRESHOLD = 11.6f
        const val STEP_MIN_INTERVAL_MS = 280L
        const val VEHICLE_CONFIRM_WINDOWS = 4
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val isRunning = AtomicBoolean(false)
    private var isRoutePlaybackActive = false

    private var stepDetectorSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    @Volatile private var currentLat: Double = 0.0
    @Volatile private var currentLon: Double = 0.0
    @Volatile private var currentHeading: Float = 0.0f
    private var lastMotionTimestamp: Long = 0L
    private var lastStepTimestamp: Long = 0L
    private var lastVehicleVibrationTimestamp: Long = 0L
    @Volatile private var lastReportedSpeed: Float = 0f

    private var initialStepCount = -1f
    private var lastStepCount = -1f
    private var lastPeakTime = 0L
    private var lastMagnitude = 9.8f

    private val accelWindow = FloatArray(40)
    private var accelIndex = 0
    private var accelCount = 0
    private var vehicleConfirmCount = 0

    private var vehicleTickJob: Job? = null
    private var pulseJob: Job? = null
    private val isVehicleMode = AtomicBoolean(false)
    private val advanceLock = Any()

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

    fun currentLatitude(): Double = currentLat
    fun currentLongitude(): Double = currentLon
    fun currentSpeedKmh(): Float = lastReportedSpeed
    fun currentBearing(): Float = currentHeading

    fun start(initialLat: Double, initialLon: Double, initialHeading: Float = 0.0f) {
        if (isRoutePlaybackActive) {
            Log.w(TAG, "Cannot start MotionSync: Route playback is actively running.")
            return
        }

        if (isRunning.getAndSet(true)) {
            setInitialCoordinate(initialLat, initialLon, initialHeading)
            emitNow()
            return
        }

        currentLat = initialLat
        currentLon = initialLon
        currentHeading = initialHeading
        lastMotionTimestamp = System.currentTimeMillis()
        lastStepTimestamp = 0L
        lastVehicleVibrationTimestamp = 0L
        lastReportedSpeed = 0f
        initialStepCount = -1f
        lastStepCount = -1f
        accelIndex = 0
        accelCount = 0
        vehicleConfirmCount = 0
        isVehicleMode.set(false)

        if (sensorManager != null) {
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            if (stepDetectorSensor != null) {
                sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (stepCounterSensor != null) {
                sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            linearAccelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            rotationVectorSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        emitNow()
        startVehicleDetectionLoop()
        startAnchorPulse()
        Log.i(TAG, "MotionSync started at $initialLat, $initialLon")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        sensorManager?.unregisterListener(this)
        vehicleTickJob?.cancel()
        vehicleTickJob = null
        pulseJob?.cancel()
        pulseJob = null
        isVehicleMode.set(false)
        lastReportedSpeed = 0f
    }

    private fun emitNow() {
        val lat = currentLat
        val lon = currentLon
        val heading = currentHeading
        val speed = lastReportedSpeed
        scope.launch(Dispatchers.Main) {
            onLocationUpdated(lat, lon, heading, speed)
        }
    }

    private fun startAnchorPulse() {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            while (isActive && isRunning.get()) {
                emitNow()
                delay(200L)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning.get() || isRoutePlaybackActive || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> onPhysicalStepDetected()
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
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val mag = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                maybeDetectPeakStep(mag + 9.81f)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                maybeDetectPeakStep(magnitude)

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
                var azimuthDeg = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                if (azimuthDeg < 0f) azimuthDeg += 360f
                currentHeading = azimuthDeg
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun maybeDetectPeakStep(magnitude: Float) {
        val now = System.currentTimeMillis()
        val rising = magnitude > STEP_PEAK_THRESHOLD && lastMagnitude <= STEP_PEAK_THRESHOLD
        if (rising && now - lastPeakTime >= STEP_MIN_INTERVAL_MS && now - lastStepTimestamp >= STEP_MIN_INTERVAL_MS) {
            lastPeakTime = now
            onPhysicalStepDetected()
        }
        lastMagnitude = magnitude
    }

    private fun onPhysicalStepDetected() {
        val now = System.currentTimeMillis()
        if (now - lastStepTimestamp < 180L) return
        lastStepTimestamp = now
        lastMotionTimestamp = now
        isVehicleMode.set(false)
        vehicleConfirmCount = 0

        val jitter = (Random.nextDouble(-STRIDE_JITTER_RATIO, STRIDE_JITTER_RATIO)) * DEFAULT_STRIDE_LENGTH_METERS
        val stepDistance = DEFAULT_STRIDE_LENGTH_METERS + jitter
        advanceLocation(stepDistance, WALK_SPEED_KMH)
    }

    private fun checkAccelerometerMotionState() {
        if (accelCount < 16) return

        var sum = 0f
        for (i in 0 until accelCount) sum += accelWindow[i]
        val mean = sum / accelCount

        var varianceSum = 0f
        for (i in 0 until accelCount) {
            val diff = accelWindow[i] - mean
            varianceSum += diff * diff
        }
        val variance = varianceSum / accelCount

        val now = System.currentTimeMillis()
        val timeSinceStep = if (lastStepTimestamp == 0L) Long.MAX_VALUE else now - lastStepTimestamp

        if (variance > VEHICLE_MIN_VARIANCE && timeSinceStep > 2500L) {
            vehicleConfirmCount++
            lastVehicleVibrationTimestamp = now
            if (vehicleConfirmCount >= VEHICLE_CONFIRM_WINDOWS) {
                isVehicleMode.set(true)
                lastMotionTimestamp = now
            }
        } else if (variance < STATIONARY_MAX_VARIANCE) {
            vehicleConfirmCount = 0
            isVehicleMode.set(false)
        } else {
            vehicleConfirmCount = (vehicleConfirmCount - 1).coerceAtLeast(0)
        }
    }

    private fun startVehicleDetectionLoop() {
        vehicleTickJob?.cancel()
        vehicleTickJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val timeSinceStep = if (lastStepTimestamp == 0L) Long.MAX_VALUE else now - lastStepTimestamp
                val timeSinceVibration = if (lastVehicleVibrationTimestamp == 0L) Long.MAX_VALUE else now - lastVehicleVibrationTimestamp

                val isActivelyInVehicle = isVehicleMode.get() && timeSinceVibration < VEHICLE_VIBRATION_TIMEOUT_MS
                if (!isActivelyInVehicle) {
                    isVehicleMode.set(false)
                }

                val isActivelyWalking = timeSinceStep < STEP_MOTION_TIMEOUT_MS

                if (isActivelyInVehicle) {
                    lastMotionTimestamp = now
                    val vehicleDistance = (VEHICLE_MAX_SPEED_KMH * 1000.0 / 3600.0) * 0.5
                    advanceLocation(vehicleDistance, VEHICLE_MAX_SPEED_KMH)
                } else if (!isActivelyWalking) {
                    if (lastReportedSpeed > 0f) {
                        lastReportedSpeed = 0f
                        emitNow()
                    }
                }
            }
        }
    }

    private fun advanceLocation(distanceMeters: Double, speedKmh: Float) {
        scope.launch {
            synchronized(advanceLock) {
                // snapshot taken inside coroutine after lock via local copies below
            }
            val fromLat = currentLat
            val fromLon = currentLon
            val fromHeading = currentHeading

            val db = AppDatabase.getInstance(context)
            val settings = db.automationSettingsDao().getSettings()

            val terrainLockEnabled = settings?.terrainLockEnabled ?: true
            var nextLat = fromLat
            var nextLon = fromLon
            var nextHeading = fromHeading

            if (terrainLockEnabled) {
                val stepResult = TerrainLockEngine.evaluateStep(
                    context = context,
                    currentLat = fromLat,
                    currentLon = fromLon,
                    currentHeading = fromHeading,
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
                        lastReportedSpeed = 0f
                        db.automationLogDao().logEvent(
                            AutomationLogEntity(
                                source = "TERRAIN",
                                targetSummary = "Hold Position ($fromLat, $fromLon)",
                                details = stepResult.reason
                            )
                        )
                        return@launch
                    }
                }
            } else {
                val (destLat, destLon) = GeoUtils.computeDestinationPoint(fromLat, fromLon, fromHeading, distanceMeters)
                nextLat = destLat
                nextLon = destLon
            }

            currentLat = nextLat
            currentLon = nextLon
            currentHeading = nextHeading
            lastReportedSpeed = speedKmh
            emitNow()
        }
    }
}
