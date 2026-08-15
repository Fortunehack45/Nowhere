package com.fakegps.mocklocation.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.engine.GeoUtils
import com.fakegps.mocklocation.engine.MockLocationEngine
import com.fakegps.mocklocation.engine.RealismLayer
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.RouteSimulator
import com.fakegps.mocklocation.simulator.SimulationMode
import com.fakegps.mocklocation.simulator.TransportMode
import com.fakegps.mocklocation.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockLocationService : Service() {

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
        const val EXTRA_TRANSPORT_MODE = "extra_transport_mode"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    private val binder = LocalBinder()
    private lateinit var engine: MockLocationEngine
    private lateinit var realismLayer: RealismLayer
    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var settingsPrefs: AppSettingsPreferences

    // Dedicated independent coroutine scope that runs continuously in background
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null

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
        MockLocationServiceReceiver.activeService = this
        settingsPrefs = AppSettingsPreferences(this)
        realismLayer = RealismLayer(settingsPrefs)
        engine = MockLocationEngine(this, realismLayer, settingsPrefs)
        sessionPrefs = SessionPreferences(this)
        createNotificationChannel()
        acquireWakeLock()
        startForegroundNotification("Nowhere Location Service", "Ready & Active")
    }

    private fun updateAllWidgets() {
        com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.updateAllWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereRouteWidgetProvider.updateAllRouteWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereSearchWidgetProvider.updateAllSearchWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Return true to allow rebind without killing the foreground service
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
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
            ACTION_START_ROUTE -> {
                val waypoints = sessionPrefs.getWaypoints()
                val speed = intent.getFloatExtra(EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                val looping = intent.getBooleanExtra(EXTRA_IS_LOOPING, sessionPrefs.isLooping)
                val modeName = intent.getStringExtra(EXTRA_TRANSPORT_MODE) ?: TransportMode.VEHICLE.name
                val transportMode = try {
                    TransportMode.valueOf(modeName)
                } catch (e: Exception) {
                    TransportMode.VEHICLE
                }
                if (waypoints.size >= 2) {
                    startRoute(waypoints, speed, looping, transportMode)
                }
            }
            ACTION_START_JOYSTICK -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                val speed = intent.getFloatExtra(EXTRA_SPEED_KMH, sessionPrefs.lastSpeedKmh)
                startJoystick(lat, lon, speed)
            }
            else -> {
                if (sessionPrefs.isSessionActive) {
                    restoreActiveSession()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: App swiped away or closed. Service will remain active in background.")
        if (sessionPrefs.isSessionActive) {
            acquireWakeLock()
            val restartServiceIntent = Intent(applicationContext, MockLocationService::class.java).apply {
                action = ACTION_RESTORE_SESSION
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                99,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000L,
                restartPendingIntent
            )
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nowhere::MockLocationLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (ignored: Exception) {}
    }

    private fun startForegroundNotification(locationTitle: String, contentSubtitle: String = "Location Injected & Active") {
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
            .setContentTitle(locationTitle)
            .setContentText(contentSubtitle)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(ContextCompat.getColor(this, R.color.primary))
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

    private fun updateLocationNotification(lat: Double, lon: Double, modeDescription: String = "Active") {
        serviceScope.launch {
            val placeName = com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationName(
                this@MockLocationService,
                lat,
                lon
            )
            val coordsText = String.format("%.5f°, %.5f° • %s", lat, lon, modeDescription)

            val stopIntent = Intent(this@MockLocationService, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this@MockLocationService,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openAppIntent = Intent(this@MockLocationService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                this@MockLocationService,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this@MockLocationService, CHANNEL_ID)
                .setContentTitle(placeName)
                .setContentText(coordsText)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(this@MockLocationService, R.color.primary))
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(placeName)
                        .bigText("$coordsText\nGPS Mocking active in background across all apps.")
                )

            if (activeMode is SimulationMode.Route) {
                val isPaused = (_serviceState.value as? ServiceState.Running)?.isPaused == true
                val pauseResumeIntent = Intent(this@MockLocationService, MockLocationService::class.java).apply {
                    action = if (isPaused) ACTION_RESUME_ROUTE else ACTION_PAUSE_ROUTE
                }
                val pauseResumePendingIntent = PendingIntent.getService(
                    this@MockLocationService,
                    2,
                    pauseResumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val actionTitle = if (isPaused) "Resume" else "Pause"
                val actionIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
                builder.addAction(actionIcon, actionTitle, pauseResumePendingIntent)
            }

            builder.addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, builder.build())
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
        acquireWakeLock()
        activeMode = SimulationMode.Fixed(latitude, longitude, altitude)
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "FIXED"
        sessionPrefs.lastLatitude = latitude
        sessionPrefs.lastLongitude = longitude
        sessionPrefs.lastAltitude = altitude
        updateAllWidgets()

        startForegroundNotification(String.format("Fixed: %.5f, %.5f", latitude, longitude))
        updateLocationNotification(latitude, longitude, "Teleported / Fixed")

        simulationJob = serviceScope.launch {
            while (isActive) {
                val result = engine.setLocation(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    speed = 0.0f,
                    bearing = 0.0f,
                    applyStationaryJitter = settingsPrefs.randomizeJitter
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
                delay(250L) // 4Hz high-frequency continuous provider lock
            }
        }
    }

    fun startRoute(
        waypoints: List<RoutePoint>,
        speedKmh: Float = 20.0f,
        isLooping: Boolean = true,
        transportMode: TransportMode = TransportMode.VEHICLE
    ) {
        if (waypoints.size < 2) return
        stopCurrentLoop()
        acquireWakeLock()

        val simulator = RouteSimulator(waypoints, speedKmh, isLooping, transportMode)
        routeSimulator = simulator
        activeMode = SimulationMode.Route(waypoints, speedKmh, isLooping)

        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "ROUTE"
        sessionPrefs.lastSpeedKmh = speedKmh
        sessionPrefs.isLooping = isLooping
        sessionPrefs.saveWaypoints(waypoints)

        startForegroundNotification(
            String.format("Route: %d waypoints", waypoints.size),
            String.format("%.1f KM/H • %s", speedKmh, transportMode.title)
        )
        if (waypoints.isNotEmpty()) {
            updateLocationNotification(waypoints[0].latitude, waypoints[0].longitude, "Route Active (${transportMode.title})")
        }
        updateAllWidgets()

        simulationJob = serviceScope.launch {
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
        updateAllWidgets()
    }

    fun resumeRoute() {
        routeSimulator?.resume()
        val current = _serviceState.value
        if (current is ServiceState.Running) {
            _serviceState.value = current.copy(isPaused = false)
        }
        updateAllWidgets()
    }

    fun startJoystick(startLat: Double, startLon: Double, speedKmh: Float = 10.0f) {
        stopCurrentLoop()
        acquireWakeLock()
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
        updateAllWidgets()

        startForegroundNotification(
            String.format("Joystick: %.5f, %.5f", startLat, startLon),
            String.format("Speed: %.1f KM/H", speedKmh)
        )
        updateLocationNotification(startLat, startLon, "Joystick Active")

        simulationJob = serviceScope.launch {
            val deltaSeconds = 0.1
            while (isActive) {
                if (joystickMagnitude > 0.01f) {
                    val speedMps = (joystickSpeedKmh * 1000f / 3600f) * joystickMagnitude
                    val distanceMeters = speedMps * deltaSeconds

                    val (newLat, newLon) = GeoUtils.computeDestinationPoint(
                        joystickLat,
                        joystickLon,
                        joystickAngleDeg,
                        distanceMeters
                    )
                    joystickLat = newLat
                    joystickLon = newLon

                    val result = engine.setLocation(
                        latitude = newLat,
                        longitude = newLon,
                        altitude = 15.0,
                        speed = speedMps,
                        bearing = joystickAngleDeg,
                        applyStationaryJitter = false
                    )

                    if (result.isSuccess) {
                        _serviceState.value = ServiceState.Running(
                            mode = activeMode,
                            latitude = newLat,
                            longitude = newLon,
                            altitude = 15.0,
                            speedMps = speedMps,
                            bearingDegrees = joystickAngleDeg
                        )
                    }
                } else {
                    engine.setLocation(
                        latitude = joystickLat,
                        longitude = joystickLon,
                        altitude = 15.0,
                        speed = 0.0f,
                        bearing = joystickAngleDeg,
                        applyStationaryJitter = false
                    )
                }

                delay(100L)
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
        releaseWakeLock()
        engine.stop()
        sessionPrefs.isSessionActive = false
        _serviceState.value = ServiceState.Idle
        updateAllWidgets()
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
                        sessionPrefs.isLooping,
                        TransportMode.VEHICLE
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
        MockLocationServiceReceiver.activeService = null
        stopSpoofing()
        serviceJob.cancel()
        super.onDestroy()
    }
}
