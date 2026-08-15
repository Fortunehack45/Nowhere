package com.fakegps.mocklocation.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.engine.MockLocationEngine
import com.fakegps.mocklocation.engine.RealismLayer
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.RouteSimulator
import com.fakegps.mocklocation.simulator.SimulationMode
import com.fakegps.mocklocation.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockLocationService : LifecycleService() {

    companion object {
        private const val TAG = "MockLocationService"
        const val CHANNEL_ID = "mock_location_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_FIXED = "action_start_fixed"
        const val ACTION_START_ROUTE = "action_start_route"
        const val ACTION_START_JOYSTICK = "action_start_joystick"
        const val ACTION_STOP = "action_stop"
        const val ACTION_PAUSE_ROUTE = "action_pause_route"
        const val ACTION_RESUME_ROUTE = "action_resume_route"
        const val ACTION_RESTORE_SESSION = "action_restore_session"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"
        const val EXTRA_IS_LOOPING = "extra_is_looping"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    private val binder = LocalBinder()
    private lateinit var engine: MockLocationEngine
    private lateinit var realismLayer: RealismLayer
    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var settingsPrefs: AppSettingsPreferences

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private var simulationJob: Job? = null
    private var activeMode: SimulationMode = SimulationMode.Idle
    private var routeSimulator: RouteSimulator? = null

    // Joystick runtime state
    private var joystickLat: Double = 0.0
    private var joystickLon: Double = 0.0
    private var joystickSpeedKmh: Float = 5.0f
    private var joystickAngleDeg: Float = 0.0f
    private var joystickMagnitude: Float = 0.0f

    override fun onCreate() {
        super.onCreate()
        settingsPrefs = AppSettingsPreferences(this)
        realismLayer = RealismLayer(settingsPrefs)
        engine = MockLocationEngine(this, realismLayer, settingsPrefs)
        sessionPrefs = SessionPreferences(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSpoofing()
            ACTION_PAUSE_ROUTE -> pauseRoute()
            ACTION_RESUME_ROUTE -> resumeRoute()
            ACTION_RESTORE_SESSION -> restoreActiveSession()
            ACTION_START_FIXED -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                val alt = intent.getDoubleExtra(EXTRA_ALTITUDE, sessionPrefs.lastAltitude)
                startFixed(lat, lon, alt)
            }
            ACTION_START_JOYSTICK -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                val speed = intent.getFloatExtra(EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                startJoystick(lat, lon, speed)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(contentSummary: String) {
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nowhere • Active")
            .setContentText(contentSummary)
            .setSmallIcon(R.drawable.ic_nowhere_logo)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nowhere Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when Nowhere GPS simulation is actively running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun startFixed(latitude: Double, longitude: Double, altitude: Double = 15.0) {
        stopCurrentLoop()
        activeMode = SimulationMode.Fixed(latitude, longitude, altitude)
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "FIXED"
        sessionPrefs.lastLatitude = latitude
        sessionPrefs.lastLongitude = longitude
        sessionPrefs.lastAltitude = altitude

        startForegroundNotification(String.format("Fixed: %.5f, %.5f", latitude, longitude))

        simulationJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val result = engine.setLocation(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    speed = 0.0f,
                    bearing = 0.0f,
                    applyStationaryJitter = true
                )

                if (result.isFailure) {
                    val error = result.exceptionOrNull() as? com.fakegps.mocklocation.engine.MockLocationError
                        ?: com.fakegps.mocklocation.engine.MockLocationError.InternalError("Failed setting location")
                    _serviceState.value = ServiceState.Error(error)
                    stopSpoofing()
                    break
                } else {
                    val loc = result.getOrNull()
                    if (loc != null) {
                        _serviceState.value = ServiceState.Running(
                            mode = activeMode,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = loc.altitude,
                            speedMps = 0.0f,
                            bearingDegrees = 0.0f
                        )
                    }
                }
                delay(realismLayer.getAdaptiveIntervalMs(isMoving = false))
            }
        }
    }

    fun startRoute(waypoints: List<RoutePoint>, speedKmh: Float = 20.0f, isLooping: Boolean = true) {
        if (waypoints.size < 2) return
        stopCurrentLoop()

        val simulator = RouteSimulator(waypoints, speedKmh, isLooping)
        routeSimulator = simulator
        activeMode = SimulationMode.Route(waypoints, speedKmh, isLooping)

        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "ROUTE"
        sessionPrefs.lastSpeedKmh = speedKmh
        sessionPrefs.isLooping = isLooping
        sessionPrefs.saveWaypoints(waypoints)

        startForegroundNotification(String.format("Route: %d waypoints (%.1f km/h)", waypoints.size, speedKmh))

        simulationJob = lifecycleScope.launch(Dispatchers.Default) {
            val stepSeconds = 1.0
            while (isActive) {
                val simLoc = simulator.tick(stepSeconds)
                if (simLoc != null) {
                    val result = engine.setLocation(
                        latitude = simLoc.latitude,
                        longitude = simLoc.longitude,
                        altitude = simLoc.altitude,
                        speed = simLoc.speedMps,
                        bearing = simLoc.bearingDegrees,
                        applyStationaryJitter = false
                    )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull() as? com.fakegps.mocklocation.engine.MockLocationError
                            ?: com.fakegps.mocklocation.engine.MockLocationError.InternalError("Location injection failed")
                        _serviceState.value = ServiceState.Error(error)
                        stopSpoofing()
                        break
                    } else {
                        _serviceState.value = ServiceState.Running(
                            mode = activeMode,
                            latitude = simLoc.latitude,
                            longitude = simLoc.longitude,
                            altitude = simLoc.altitude,
                            speedMps = simLoc.speedMps,
                            bearingDegrees = simLoc.bearingDegrees,
                            isPaused = simulator.isPaused()
                        )
                    }

                    if (simLoc.isCompleted) {
                        Log.d(TAG, "Route simulation completed.")
                        stopSpoofing()
                        break
                    }
                }
                delay(realismLayer.getAdaptiveIntervalMs(isMoving = true))
            }
        }
    }

    fun pauseRoute() {
        routeSimulator?.pause()
        val current = _serviceState.value
        if (current is ServiceState.Running) {
            _serviceState.value = current.copy(isPaused = true)
        }
    }

    fun resumeRoute() {
        routeSimulator?.resume()
        val current = _serviceState.value
        if (current is ServiceState.Running) {
            _serviceState.value = current.copy(isPaused = false)
        }
    }

    fun startJoystick(startLat: Double, startLon: Double, speedKmh: Float = 10.0f) {
        stopCurrentLoop()
        joystickLat = startLat
        joystickLon = startLon
        joystickSpeedKmh = speedKmh
        joystickAngleDeg = 0.0f
        joystickMagnitude = 0.0f

        activeMode = SimulationMode.Joystick(startLat, startLon, speedKmh)
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "JOYSTICK"
        sessionPrefs.lastLatitude = startLat
        sessionPrefs.lastLongitude = startLon
        sessionPrefs.lastSpeedKmh = speedKmh

        startForegroundNotification(String.format("Joystick: %.5f, %.5f", startLat, startLon))

        simulationJob = lifecycleScope.launch(Dispatchers.Default) {
            val stepSeconds = 1.0
            while (isActive) {
                val isMoving = joystickMagnitude > 0.05f
                val speedMps = if (isMoving) (joystickSpeedKmh * 1000f / 3600f) * joystickMagnitude else 0.0f

                if (isMoving) {
                    val distanceMeters = speedMps * stepSeconds
                    val (nextLat, nextLon) = GeoUtils.computeDestinationPoint(
                        joystickLat,
                        joystickLon,
                        joystickAngleDeg,
                        distanceMeters
                    )
                    joystickLat = nextLat
                    joystickLon = nextLon
                }

                val result = engine.setLocation(
                    latitude = joystickLat,
                    longitude = joystickLon,
                    speed = speedMps,
                    bearing = joystickAngleDeg,
                    applyStationaryJitter = !isMoving
                )

                if (result.isFailure) {
                    val error = result.exceptionOrNull() as? com.fakegps.mocklocation.engine.MockLocationError
                        ?: com.fakegps.mocklocation.engine.MockLocationError.InternalError("Joystick update failed")
                    _serviceState.value = ServiceState.Error(error)
                    stopSpoofing()
                    break
                } else {
                    _serviceState.value = ServiceState.Running(
                        mode = activeMode,
                        latitude = joystickLat,
                        longitude = joystickLon,
                        altitude = 15.0,
                        speedMps = speedMps,
                        bearingDegrees = joystickAngleDeg
                    )
                }

                val delayMs = realismLayer.getAdaptiveIntervalMs(isMoving)
                delay(delayMs)
            }
        }
    }

    fun updateJoystickVector(angleDegrees: Float, magnitude: Float, speedKmh: Float? = null) {
        joystickAngleDeg = angleDegrees
        joystickMagnitude = magnitude.coerceIn(0.0f, 1.0f)
        if (speedKmh != null) {
            joystickSpeedKmh = speedKmh
        }
    }

    fun stopSpoofing() {
        stopCurrentLoop()
        engine.stop()
        sessionPrefs.isSessionActive = false
        _serviceState.value = ServiceState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCurrentLoop() {
        simulationJob?.cancel()
        simulationJob = null
        routeSimulator = null
    }

    private fun restoreActiveSession() {
        if (!sessionPrefs.isSessionActive) return

        when (sessionPrefs.activeMode) {
            "FIXED" -> {
                startFixed(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude,
                    sessionPrefs.lastAltitude
                )
            }
            "ROUTE" -> {
                val waypoints = sessionPrefs.getWaypoints()
                if (waypoints.size >= 2) {
                    startRoute(
                        waypoints,
                        sessionPrefs.lastSpeedKmh,
                        sessionPrefs.isLooping
                    )
                }
            }
            "JOYSTICK" -> {
                startJoystick(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude,
                    sessionPrefs.lastSpeedKmh
                )
            }
        }
    }

    override fun onDestroy() {
        stopSpoofing()
        super.onDestroy()
    }
}
