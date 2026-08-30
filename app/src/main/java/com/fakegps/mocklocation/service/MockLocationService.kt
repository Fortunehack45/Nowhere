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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
        const val ACTION_EXTEND_SESSION = "action_extend_session"
        const val ACTION_RECONNECT_FALLBACK = "action_reconnect_fallback"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"
        const val EXTRA_IS_LOOPING = "extra_is_looping"
        const val EXTRA_TRANSPORT_MODE = "extra_transport_mode"
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L // 24 hours max safeguard
        private const val WAKE_LOCK_RENEWAL_INTERVAL_MS = 20 * 60 * 60 * 1000L // Renew every 20 hours
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
    private var wakeLockRenewalJob: Job? = null

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private var simulationJob: Job? = null
    private var activeMode: SimulationMode = SimulationMode.Idle
    private var routeSimulator: RouteSimulator? = null

    // Idempotent stop guard — prevents double-stop from onDestroy + timer expiry racing
    private val isStopping = AtomicBoolean(false)

    // Joystick runtime state
    private var joystickLat: Double = 0.0
    private var joystickLon: Double = 0.0
    private var joystickSpeedKmh: Float = 5.0f
    private var joystickAngleDeg: Float = 0.0f
    private var joystickMagnitude: Float = 0.0f

    internal fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    override fun onCreate() {
        super.onCreate()
        MockLocationServiceReceiver.activeService = this
        settingsPrefs = AppSettingsPreferences(this)
        realismLayer = RealismLayer(settingsPrefs)
        engine = MockLocationEngine(this, realismLayer, settingsPrefs)
        sessionPrefs = SessionPreferences(this)
        createNotificationChannel()
        acquireWakeLock()
        com.fakegps.mocklocation.hotspot.HotspotLocationServer.startServer(this)
        startForegroundNotification("Nowhere Location Service", "Ready & Active")
        if (sessionPrefs.hasValidActiveSession()) {
            SessionTimerManager.resumeExistingTimer(this)
        }
    }

    private fun updateAllWidgets() {
        com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.updateAllWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereRouteWidgetProvider.updateAllRouteWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereSearchWidgetProvider.updateAllSearchWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereWeatherWidgetProvider.updateAllWeatherWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereSessionTimerWidgetProvider.updateAllSessionWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereVpnWidgetProvider.updateAllVpnWidgets(this)
        com.fakegps.mocklocation.ui.widget.NowhereIconWidgetProvider.updateAllIconWidgets(this)
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
            ACTION_EXTEND_SESSION -> {
                val extraMillis = intent.getLongExtra("extra_duration_millis", SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)
                SessionTimerManager.extendSession(this, extraMillis)
            }
            ACTION_RECONNECT_FALLBACK -> {
                SessionTimerManager.startTimer(this, SessionPreferences.RECONNECT_FALLBACK_DURATION_MILLIS)
                restoreActiveSession()
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager?.canScheduleExactAlarms() == true) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1000L,
                        restartPendingIntent
                    )
                } else {
                    Log.w(TAG, "Exact alarm permission not granted — restart-on-kill will not reliably work on this session.")
                    // Fall back to inexact as better-than-nothing; do not pretend this will reliably fire.
                    alarmManager?.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1000L,
                        restartPendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
            } else {
                alarmManager?.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 1000L,
                    restartPendingIntent
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "System low memory signal ($level). Preserving core simulation loop and location engine.")
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory received. Ensuring location simulation remains active.")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nowhere::MockLocationLock").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                Log.d(TAG, "WakeLock acquired with 24h safeguard.")
            }
            startWakeLockRenewalTimer()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }
    }

    private fun startWakeLockRenewalTimer() {
        if (wakeLockRenewalJob?.isActive == true) return
        wakeLockRenewalJob = serviceScope.launch {
            try {
                while (isActive) {
                    delay(WAKE_LOCK_RENEWAL_INTERVAL_MS)
                    if (sessionPrefs.isSessionActive && wakeLock?.isHeld == true) {
                        Log.i(TAG, "Periodic wake lock renewal: refreshing 24h hold for active session.")
                        try {
                            wakeLock?.release()
                            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                            Log.i(TAG, "WakeLock successfully renewed.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to renew WakeLock: ${e.message}", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Wake lock renewal timer encountered error: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLockRenewalJob?.cancel()
            wakeLockRenewalJob = null
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
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
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        scheduleWatchdog()
    }

    private fun updateLocationNotification(lat: Double, lon: Double, modeDescription: String = "Active") {
        serviceScope.launch {
            try {
                val cachedName = com.fakegps.mocklocation.util.LocationNameResolver.getCachedLocationName(lat, lon)
                val placeName = if (!cachedName.isNullOrBlank()) {
                    cachedName
                } else {
                    // Trigger background resolution for future ticks
                    serviceScope.launch(Dispatchers.IO) {
                        com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationName(
                            this@MockLocationService,
                            lat,
                            lon
                        )
                    }
                    String.format(Locale.US, "%.5f°, %.5f°", lat, lon)
                }

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

                val runningState = _serviceState.value as? ServiceState.Running
                val isRoute = activeMode is SimulationMode.Route && runningState != null && runningState.totalDistanceMeters > 0
                val progress = if (isRoute && runningState!!.totalDistanceMeters > 0) {
                    ((runningState.distanceCoveredMeters / runningState.totalDistanceMeters) * 100).toInt().coerceIn(0, 100)
                } else 0

                val coveredStr = if (isRoute) String.format(Locale.US, "%.2f km", runningState!!.distanceCoveredMeters / 1000.0) else ""
                val totalStr = if (isRoute) String.format(Locale.US, "%.2f km", runningState!!.totalDistanceMeters / 1000.0) else ""
                val remainingStr = if (isRoute) String.format(Locale.US, "%.2f km", runningState!!.distanceRemainingMeters / 1000.0) else ""
                val speedKmh = if (runningState != null) runningState.speedMps * 3.6f else 0.0f
                val speedMps = if (runningState != null) runningState.speedMps.coerceAtLeast(0.1f) else 0.1f
                val etaSec = if (isRoute && speedMps > 0.3f && runningState!!.distanceRemainingMeters > 5.0) {
                    (runningState.distanceRemainingMeters / speedMps).toLong()
                } else 0L
                val etaStr = if (isRoute) formatEta(etaSec) else ""

                val notifTitle = if (isRoute) {
                    "📍 Route: $coveredStr / $totalStr ($progress%)"
                } else {
                    placeName
                }

                val notifText = if (isRoute) {
                    "⏱️ ETA: $etaStr • Speed: ${speedKmh.toInt()} km/h"
                } else {
                    coordsText
                }

                val routeDetails = if (isRoute) {
                    "\n📍 Route: $coveredStr / $totalStr ($progress%) • $remainingStr left\n⏱️ ETA: $etaStr • Speed: ${speedKmh.toInt()} km/h"
                } else ""

                val builder = NotificationCompat.Builder(this@MockLocationService, CHANNEL_ID)
                    .setContentTitle(notifTitle)
                    .setContentText(notifText)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setColor(ContextCompat.getColor(this@MockLocationService, R.color.primary))
                    .setContentIntent(openAppPendingIntent)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_LOW)

                if (isRoute) {
                    builder.setProgress(100, progress, false)
                }

                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(notifTitle)
                        .bigText("$coordsText$routeDetails\nGPS Mocking active in background across all apps.")
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
                manager?.notify(NOTIFICATION_ID, builder.build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Non-fatal error updating notification: ${e.message}")
            }
        }
    }

    private fun scheduleWatchdog() {
        // Watchdog: only schedule a one-shot keepalive if no session is currently running.
        // Uses inexact alarm to avoid SCHEDULE_EXACT_ALARM permission requirement on Android 12+.
        if (!sessionPrefs.isSessionActive) return
        if (_serviceState.value is ServiceState.Running) return // Already running — no need
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val watchdogIntent = Intent(applicationContext, MockLocationService::class.java).apply {
                action = ACTION_RESTORE_SESSION
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                999,
                watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerTime = android.os.SystemClock.elapsedRealtime() + 90_000L // 90s keepalive
            // Use setAndAllowWhileIdle (inexact) — avoids SCHEDULE_EXACT_ALARM SecurityException on API 31+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Watchdog alarm permission denied — service will rely on START_STICKY for self-recovery.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule watchdog alarm: ${e.message}")
        }
    }

    private fun cancelWatchdog() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val watchdogIntent = Intent(applicationContext, MockLocationService::class.java).apply {
                action = ACTION_RESTORE_SESSION
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                999,
                watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager?.cancel(pendingIntent)
        } catch (ignored: Exception) {}
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
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun startFixed(latitude: Double, longitude: Double, altitude: Double = 15.0) {
        isStopping.set(false)
        stopCurrentLoop()
        acquireWakeLock()
        activeMode = SimulationMode.Fixed(latitude, longitude, altitude)
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "FIXED"
        sessionPrefs.lastLatitude = latitude
        sessionPrefs.lastLongitude = longitude
        sessionPrefs.lastAltitude = altitude
        updateAllWidgets()

        SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        // Automatically activate VPN — wrapped in try-catch to prevent VPN errors crashing simulation
        try {
            val bestNode = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(latitude, longitude)
            sessionPrefs.activeIpNodeId = bestNode.id
            sessionPrefs.isIpMaskingEnabled = true
            com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, bestNode.id)
        } catch (e: Exception) {
            Log.w(TAG, "VPN auto-start failed (non-fatal): ${e.message}")
        }

        serviceScope.launch(Dispatchers.IO) {
            com.fakegps.mocklocation.weather.WeatherManager.fetchWeather(this@MockLocationService, latitude, longitude)
        }

        com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(latitude, longitude, altitude, 0.0f, 0.0f)
        startForegroundNotification(String.format("Fixed: %.5f, %.5f", latitude, longitude))
        updateLocationNotification(latitude, longitude, "Teleported / Fixed")

        simulationJob = serviceScope.launch {
            try {
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
                        val error = result.exceptionOrNull()
                        Log.w(TAG, "Transient injection error: ${error?.message}, retrying...")
                        delay(250L)
                        continue
                    } else {
                        val loc = result.getOrNull()
                        if (loc != null) {
                            com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(latitude, longitude, altitude, 0.0f, 0.0f)
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = latitude,
                                longitude = longitude,
                                altitude = altitude,
                                speedMps = 0.0f,
                                bearingDegrees = 0.0f
                            )
                        }
                    }
                    delay(500L) // High-frequency 500ms zero-dropout provider lock
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Uncaught fatal error in fixed simulation loop: ${t.message}", t)
                stopSpoofing()
            }
        }
    }

    fun startRoute(
        waypoints: List<RoutePoint>,
        speedKmh: Float = 20.0f,
        isLooping: Boolean = true,
        transportMode: TransportMode = TransportMode.VEHICLE,
        forceRestart: Boolean = false
    ) {
        if (waypoints.size < 2) return

        val currentMode = activeMode
        if (!forceRestart && currentMode is SimulationMode.Route && currentMode.waypoints == waypoints && simulationJob?.isActive == true) {
            updateRouteSpeed(speedKmh)
            updateRouteLooping(isLooping)
            return
        }

        isStopping.set(false)
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

        SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        // Automatically activate VPN — wrapped in try-catch to prevent VPN errors crashing simulation
        if (waypoints.isNotEmpty()) {
            try {
                val bestNode = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(waypoints[0].latitude, waypoints[0].longitude)
                sessionPrefs.activeIpNodeId = bestNode.id
                sessionPrefs.isIpMaskingEnabled = true
                com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, bestNode.id)
            } catch (e: Exception) {
                Log.w(TAG, "VPN auto-start failed on route (non-fatal): ${e.message}")
            }
        }

        val totalRouteDist = simulator.totalDistanceMeters
        sendRouteStartedNotification(waypoints.size, totalRouteDist, speedKmh, transportMode.title)

        startForegroundNotification(
            String.format("Route: %d waypoints", waypoints.size),
            String.format("%.1f KM/H • %s", speedKmh, transportMode.title)
        )
        if (waypoints.isNotEmpty()) {
            updateLocationNotification(waypoints[0].latitude, waypoints[0].longitude, "Route Active (${transportMode.title})")
        }
        updateAllWidgets()

        simulationJob = serviceScope.launch {
            try {
                var lastTickTime = android.os.SystemClock.elapsedRealtime()
                var lastWidgetUpdateTime = 0L
                var lastNotificationUpdateTime = 0L
                var lastWeatherUpdateTime = 0L
                var lastWeatherLat = 0.0
                var lastWeatherLon = 0.0

                while (isActive) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dt = ((now - lastTickTime) / 1000.0).coerceIn(0.05, 3.0)
                    lastTickTime = now

                    val simLoc = simulator.tick(dt)
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
                            val error = result.exceptionOrNull()
                            Log.w(TAG, "Transient route injection error: ${error?.message}, retrying...")
                        } else {
                            com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(simLoc.latitude, simLoc.longitude, simLoc.altitude, simLoc.speedMps, simLoc.bearingDegrees)
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = simLoc.latitude,
                                longitude = simLoc.longitude,
                                altitude = simLoc.altitude,
                                speedMps = simLoc.speedMps,
                                bearingDegrees = simLoc.bearingDegrees,
                                isPaused = simulator.isPaused(),
                                totalDistanceMeters = simLoc.totalDistanceMeters,
                                distanceCoveredMeters = simLoc.distanceCoveredMeters,
                                distanceRemainingMeters = simLoc.distanceRemainingMeters
                            )
                            sessionPrefs.lastLatitude = simLoc.latitude
                            sessionPrefs.lastLongitude = simLoc.longitude
                            sessionPrefs.routeTotalDistanceMeters = simLoc.totalDistanceMeters
                            sessionPrefs.routeCoveredDistanceMeters = simLoc.distanceCoveredMeters
                            sessionPrefs.routeRemainingDistanceMeters = simLoc.distanceRemainingMeters

                            // Real-time home screen widget update in background (every 1s)
                            if (now - lastWidgetUpdateTime >= 1000L) {
                                lastWidgetUpdateTime = now
                                updateAllWidgets()
                            }

                            // Real-time foreground notification progress update in background (every 1s)
                            if (now - lastNotificationUpdateTime >= 1000L) {
                                lastNotificationUpdateTime = now
                                updateLocationNotification(simLoc.latitude, simLoc.longitude, "Route Active (${transportMode.title})")
                            }

                            // Periodic weather update during route travel (every 45s or when moved > 0.05 degrees ~ 5km)
                            val distShift = kotlin.math.abs(simLoc.latitude - lastWeatherLat) + kotlin.math.abs(simLoc.longitude - lastWeatherLon)
                            if (now - lastWeatherUpdateTime >= 45000L || (distShift > 0.05 && now - lastWeatherUpdateTime >= 15000L)) {
                                lastWeatherUpdateTime = now
                                lastWeatherLat = simLoc.latitude
                                lastWeatherLon = simLoc.longitude
                                serviceScope.launch(Dispatchers.IO) {
                                    try {
                                        com.fakegps.mocklocation.weather.WeatherManager.fetchWeather(this@MockLocationService, simLoc.latitude, simLoc.longitude)
                                    } catch (ignored: Exception) {}
                                }
                            }
                        }

                        if (simLoc.isCompleted) {
                            Log.i(TAG, "Route completed: automatically transitioning to Fixed mock location at destination: (${simLoc.latitude}, ${simLoc.longitude})")
                            sendRouteCompletedNotification(simLoc.totalDistanceMeters, simLoc.latitude, simLoc.longitude)
                            updateAllWidgets()
                            startFixed(simLoc.latitude, simLoc.longitude, simLoc.altitude)
                            break
                        }
                    }
                    delay(realismLayer.getAdaptiveIntervalMs(isMoving = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Uncaught fatal error in route simulation loop: ${t.message}", t)
                stopSpoofing()
            }
        }
    }

    private fun sendRouteStartedNotification(waypointsCount: Int, totalDistanceMeters: Double, speedKmh: Float, transportMode: String) {
        try {
            val speedMps = (speedKmh / 3.6f).coerceAtLeast(0.1f)
            val etaSec = (totalDistanceMeters / speedMps).toLong()
            val etaStr = formatEta(etaSec)
            val distStr = String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000.0)

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                101,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚀 Route Simulation Started")
                .setContentText("$waypointsCount waypoints • $distStr • Speed: ${speedKmh.toInt()} km/h • ETA: $etaStr")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(this, R.color.primary))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(5003, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Route start notification error: ${e.message}")
        }
    }

    private fun sendRouteCompletedNotification(totalDistanceMeters: Double, finalLat: Double, finalLon: Double) {
        try {
            val distStr = String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000.0)
            val coordsStr = String.format(Locale.US, "%.5f, %.5f", finalLat, finalLon)

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                102,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🏁 Route Simulation Completed!")
                .setContentText("Destination reached ($coordsStr) • Total: $distStr")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(this, R.color.primary))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(5004, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Route complete notification error: ${e.message}")
        }
    }

    private fun formatEta(etaSeconds: Long): String {
        return when {
            etaSeconds <= 0 -> "Arriving"
            etaSeconds >= 3600 -> String.format(Locale.US, "%dh %02dm", etaSeconds / 3600, (etaSeconds % 3600) / 60)
            etaSeconds >= 60 -> String.format(Locale.US, "%dm %02ds", etaSeconds / 60, etaSeconds % 60)
            else -> String.format(Locale.US, "%ds", etaSeconds)
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

    fun updateRouteSpeed(speedKmh: Float) {
        routeSimulator?.targetSpeedKmh = speedKmh
        sessionPrefs.lastSpeedKmh = speedKmh
        val current = _serviceState.value
        if (current is ServiceState.Running && activeMode is SimulationMode.Route) {
            val routeMode = activeMode as SimulationMode.Route
            activeMode = routeMode.copy(speedKmh = speedKmh)
            _serviceState.value = current.copy(mode = activeMode)
        }
    }

    fun updateRouteLooping(isLooping: Boolean) {
        routeSimulator?.isLooping = isLooping
        sessionPrefs.isLooping = isLooping
        val current = _serviceState.value
        if (current is ServiceState.Running && activeMode is SimulationMode.Route) {
            val routeMode = activeMode as SimulationMode.Route
            activeMode = routeMode.copy(isLooping = isLooping)
            _serviceState.value = current.copy(mode = activeMode)
        }
    }

    fun startJoystick(startLat: Double, startLon: Double, speedKmh: Float = 10.0f) {
        isStopping.set(false)
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

        SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        // Automatically activate VPN — wrapped in try-catch to prevent VPN errors crashing simulation
        try {
            val bestNode = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(startLat, startLon)
            sessionPrefs.activeIpNodeId = bestNode.id
            sessionPrefs.isIpMaskingEnabled = true
            com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, bestNode.id)
        } catch (e: Exception) {
            Log.w(TAG, "VPN auto-start failed on joystick (non-fatal): ${e.message}")
        }

        startForegroundNotification(
            String.format("Joystick: %.5f, %.5f", startLat, startLon),
            String.format("Speed: %.1f KM/H", speedKmh)
        )
        updateLocationNotification(startLat, startLon, "Joystick Active")

        simulationJob = serviceScope.launch {
            try {
                val deltaSeconds = 0.1
                var lastWidgetUpdateTime = 0L
                var lastNotificationUpdateTime = 0L

                while (isActive) {
                    val now = android.os.SystemClock.elapsedRealtime()
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
                        sessionPrefs.lastLatitude = newLat
                        sessionPrefs.lastLongitude = newLon

                        val result = engine.setLocation(
                            latitude = newLat,
                            longitude = newLon,
                            altitude = 15.0,
                            speed = speedMps,
                            bearing = joystickAngleDeg,
                            applyStationaryJitter = false
                        )

                        if (result.isSuccess) {
                            com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(newLat, newLon, 15.0, speedMps, joystickAngleDeg)
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = newLat,
                                longitude = newLon,
                                altitude = 15.0,
                                speedMps = speedMps,
                                bearingDegrees = joystickAngleDeg
                            )

                            // Real-time home screen widget update during joystick movement
                            if (now - lastWidgetUpdateTime >= 1500L) {
                                lastWidgetUpdateTime = now
                                updateAllWidgets()
                            }

                            // Real-time notification update during joystick movement
                            if (now - lastNotificationUpdateTime >= 2500L) {
                                lastNotificationUpdateTime = now
                                updateLocationNotification(newLat, newLon, "Joystick Active (${String.format(java.util.Locale.US, "%.1f km/h", joystickSpeedKmh * joystickMagnitude)})")
                            }
                        }
                    } else {
                        val result = engine.setLocation(
                            latitude = joystickLat,
                            longitude = joystickLon,
                            altitude = 15.0,
                            speed = 0.0f,
                            bearing = joystickAngleDeg,
                            applyStationaryJitter = settingsPrefs.randomizeJitter
                        )
                        if (result.isSuccess) {
                            com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(joystickLat, joystickLon, 15.0, 0.0f, joystickAngleDeg)
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = joystickLat,
                                longitude = joystickLon,
                                altitude = 15.0,
                                speedMps = 0.0f,
                                bearingDegrees = joystickAngleDeg
                            )
                        }
                    }

                    delay(100L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Uncaught fatal error in joystick simulation loop: ${t.message}", t)
                stopSpoofing()
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
        // Idempotent guard: prevent double-stop from timer expiry and onDestroy racing
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(TAG, "stopSpoofing already in progress, skipping duplicate call.")
            return
        }
        Log.i(TAG, "stopSpoofing called. Terminating simulation and releasing resources.")
        cancelWatchdog()
        stopCurrentLoop()
        releaseWakeLock()
        try { com.fakegps.mocklocation.hotspot.HotspotLocationServer.stopServer() } catch (e: Exception) {}
        try { com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this) } catch (e: Exception) {}
        try { engine.stop() } catch (e: Exception) { Log.w(TAG, "engine.stop() error (non-fatal): ${e.message}") }
        sessionPrefs.isSessionActive = false
        SessionTimerManager.stopTimer(this)
        _serviceState.value = ServiceState.Idle
        try { updateAllWidgets() } catch (e: Exception) { Log.w(TAG, "widget update on stop (non-fatal): ${e.message}") }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        stopSelf()
    }

    private fun stopCurrentLoop() {
        simulationJob?.cancel()
        simulationJob = null
        routeSimulator = null
    }

    private fun restoreActiveSession() {
        if (!sessionPrefs.isSessionActive) return
        // If simulation is already running (e.g. watchdog fired while active), skip restore
        if (simulationJob?.isActive == true) {
            Log.d(TAG, "restoreActiveSession: simulation already active, skipping.")
            return
        }
        SessionTimerManager.resumeExistingTimer(this)

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
        // IMPORTANT: Do NOT call stopSpoofing() here — it calls stopSelf() which creates a
        // recursive destroy loop when Android system legitimately destroys the service.
        // Instead, only cancel coroutines and release resources directly.
        MockLocationServiceReceiver.activeService = null
        cancelWatchdog()
        stopCurrentLoop()
        releaseWakeLock()
        try { com.fakegps.mocklocation.hotspot.HotspotLocationServer.stopServer() } catch (e: Exception) {}
        try { com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this) } catch (e: Exception) {}
        try { engine.stop() } catch (e: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }
}
