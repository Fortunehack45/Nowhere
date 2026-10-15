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
import android.widget.RemoteViews
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
        const val CHANNEL_ID = "mock_location_foreground_channel_v4"
        private const val OLD_CHANNEL_ID = "mock_location_foreground_channel"
        private const val OLD_CHANNEL_ID_V2 = "mock_location_foreground_channel_v2"
        private const val OLD_CHANNEL_ID_V3 = "mock_location_foreground_channel_v3"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_FIXED = "action_start_fixed"
        const val ACTION_START_ROUTE = "action_start_route"
        const val ACTION_START_JOYSTICK = "action_start_joystick"
        const val ACTION_STOP = "action_stop"
        const val ACTION_PAUSE_ROUTE = "action_pause_route"
        const val ACTION_RESUME_ROUTE = "action_resume_route"
        const val ACTION_START_MOTION_SYNC = "action_start_motion_sync"
        const val ACTION_STOP_MOTION_SYNC = "action_stop_motion_sync"
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

        @Volatile
        var activeInstance: MockLocationService? = null

        fun isSimulationRunning(): Boolean {
            val service = activeInstance ?: MockLocationServiceReceiver.activeService ?: return false
            if (service.isSimulationPaused) return false
            val state = service.serviceState.value
            return service.activeMode !is SimulationMode.Idle || (state is ServiceState.Running && !state.isPaused)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService {
            activeInstance = this@MockLocationService
            MockLocationServiceReceiver.activeService = this@MockLocationService
            return this@MockLocationService
        }
    }

    private val binder = LocalBinder()
    private lateinit var engine: MockLocationEngine
    private lateinit var realismLayer: RealismLayer
    private lateinit var sessionPrefs: SessionPreferences
    private lateinit var settingsPrefs: AppSettingsPreferences

    // Dedicated independent coroutine scope that runs continuously in background
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    // Dedicated high-priority hardware injection dispatcher:
    // Pinned to THREAD_PRIORITY_URGENT_DISPLAY so the Linux CPU scheduler never throttles
    // GPS injection ticks, guaranteeing 500ms delivery parity with active launcher widgets.
    private val hardwareTickExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (ignored: Throwable) {}
            runnable.run()
        }, "Nowhere-GpsTickThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val hardwareTickDispatcher = hardwareTickExecutor.asCoroutineDispatcher()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewalJob: Job? = null

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private var simulationJob: Job? = null
    var activeMode: SimulationMode = SimulationMode.Idle
    @Volatile var isSimulationPaused: Boolean = false
    private var routeSimulator: RouteSimulator? = null

    // Idempotent stop guard — prevents double-stop from onDestroy + timer expiry racing
    private val isStopping = AtomicBoolean(false)

    // Joystick runtime state
    private var joystickLat: Double = 0.0
    private var joystickLon: Double = 0.0
    private var joystickSpeedKmh: Float = 5.0f
    private var joystickAngleDeg: Float = 0.0f
    private var joystickMagnitude: Float = 0.0f

    // Unified simulation coordinate state for continuous heartbeat pulsing
    @Volatile private var currentSimLat: Double = 0.0
    @Volatile private var currentSimLon: Double = 0.0
    @Volatile private var currentSimAlt: Double = 15.0
    @Volatile private var currentSimSpeedMps: Float = 0.0f
    @Volatile private var currentSimBearing: Float = 0.0f

    // Automation Engines (Wifi triggers and Motion-linked mock movement)
    private var wifiTriggerHandler: com.fakegps.mocklocation.automation.engine.WifiTriggerHandler? = null
    private var motionSyncEngine: com.fakegps.mocklocation.automation.engine.MotionSyncEngine? = null

    // Cached PendingIntents for high-frequency sub-second notification updates (zero GC allocation)
    private var cachedStopPendingIntent: PendingIntent? = null
    private var cachedOpenAppPendingIntent: PendingIntent? = null
    private var cachedJoystickPendingIntent: PendingIntent? = null
    private var cachedPauseRoutePendingIntent: PendingIntent? = null
    private var cachedResumeRoutePendingIntent: PendingIntent? = null

    private fun getStopPendingIntent(): PendingIntent {
        if (cachedStopPendingIntent == null) {
            val stopIntent = Intent(this, MockLocationService::class.java).apply {
                action = ACTION_STOP
                setPackage(packageName)
            }
            cachedStopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return cachedStopPendingIntent!!
    }

    private fun getOpenAppPendingIntent(): PendingIntent {
        if (cachedOpenAppPendingIntent == null) {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(packageName)
            }
            cachedOpenAppPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return cachedOpenAppPendingIntent!!
    }

    private fun getJoystickPendingIntent(): PendingIntent {
        if (cachedJoystickPendingIntent == null) {
            val joystickIntent = Intent(this, com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider::class.java).apply {
                action = com.fakegps.mocklocation.ui.widget.NowhereAppWidgetProvider.ACTION_WIDGET_JOYSTICK
                setPackage(packageName)
            }
            cachedJoystickPendingIntent = PendingIntent.getBroadcast(
                this,
                30,
                joystickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return cachedJoystickPendingIntent!!
    }

    private fun getPauseResumePendingIntent(isPaused: Boolean): PendingIntent {
        return if (isPaused) {
            if (cachedResumeRoutePendingIntent == null) {
                val resumeIntent = Intent(this, MockLocationService::class.java).apply {
                    action = ACTION_RESUME_ROUTE
                    setPackage(packageName)
                }
                cachedResumeRoutePendingIntent = PendingIntent.getService(
                    this,
                    2,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            cachedResumeRoutePendingIntent!!
        } else {
            if (cachedPauseRoutePendingIntent == null) {
                val pauseIntent = Intent(this, MockLocationService::class.java).apply {
                    action = ACTION_PAUSE_ROUTE
                    setPackage(packageName)
                }
                cachedPauseRoutePendingIntent = PendingIntent.getService(
                    this,
                    2,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            cachedPauseRoutePendingIntent!!
        }
    }

    internal fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        settingsPrefs = AppSettingsPreferences(this)
        realismLayer = RealismLayer(settingsPrefs)
        engine = MockLocationEngine(this, settingsPrefs, realismLayer)
        sessionPrefs = SessionPreferences(this)
        createNotificationChannel()
        acquireWakeLock()
        com.fakegps.mocklocation.hotspot.HotspotLocationServer.startServer(this)
        startForegroundNotification("Nowhere Location Service", "Ready & Active")

        // Initialize WifiTriggerHandler and MotionSyncEngine
        wifiTriggerHandler = com.fakegps.mocklocation.automation.engine.WifiTriggerHandler(this).apply {
            start()
        }
        var lastMotionWidgetUpdateTime = 0L
        var lastMotionNotifUpdateTime = 0L
        motionSyncEngine = com.fakegps.mocklocation.automation.engine.MotionSyncEngine(this) { lat, lon, bearing, speed ->
            currentSimLat = lat
            currentSimLon = lon
            currentSimBearing = bearing
            val speedMps = speed / 3.6f
            currentSimSpeedMps = speedMps
            sessionPrefs.lastLatitude = lat
            sessionPrefs.lastLongitude = lon
            sessionPrefs.lastSpeedKmh = speed
            engine.setLocation(lat, lon, 10.0, speedMps, bearing, false)
            _serviceState.value = ServiceState.Running(
                mode = SimulationMode.Fixed(lat, lon, 10.0),
                latitude = lat,
                longitude = lon,
                altitude = 10.0,
                speedMps = speedMps,
                bearingDegrees = bearing
            )
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastMotionNotifUpdateTime >= 2000L) {
                lastMotionNotifUpdateTime = now
                updateLocationNotification(lat, lon, "Motion Sync Active")
            }
            if (now - lastMotionWidgetUpdateTime >= 4000L) {
                lastMotionWidgetUpdateTime = now
                updateAllWidgets()
            }
        }

        if (isSimulationRunning()) {
            SessionTimerManager.resumeExistingTimer(this)
        } else {
            SessionTimerManager.updateStaticState(this)
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
        com.fakegps.mocklocation.automation.widget.NowhereAutomationWidgetProvider.updateAllAutomationWidgets(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Return true to allow rebind without killing the foreground service
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        acquireWakeLock()

        // Guarantee Android's 5-second startForeground contract is immediately satisfied
        // for any startForegroundService intent, preventing RemoteServiceException crashes.
        if (intent?.action != ACTION_STOP) {
            val placeName = if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
                sessionPrefs.lastLocationName
            } else if (sessionPrefs.isSessionActive) {
                String.format(Locale.US, "%.5f°, %.5f°", sessionPrefs.lastLatitude, sessionPrefs.lastLongitude)
            } else {
                "Nowhere Location Service"
            }
            startForegroundNotification(
                "📍 $placeName",
                "Nowhere Active • Location Simulation"
            )
        }

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
            ACTION_START_MOTION_SYNC -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, sessionPrefs.lastLatitude)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, sessionPrefs.lastLongitude)
                startMotionSync(lat, lon)
            }
            ACTION_STOP_MOTION_SYNC -> {
                stopMotionSync()
            }
            null -> {
                // System restarted us after a kill (START_STICKY) -> restore the session
                Log.i(TAG, "onStartCommand: null intent received (OS restart). Checking session...")
                if (sessionPrefs.isSessionActive) {
                    restoreActiveSession()
                }
            }
            else -> {
                Log.d(TAG, "onStartCommand: Unrecognized action (${intent.action}). Skipping.")
            }
        }
        return if (sessionPrefs.isSessionActive) START_STICKY else START_NOT_STICKY
    }

    fun startMotionSync(initialLat: Double, initialLon: Double) {
        if (activeMode is SimulationMode.Route) {
            Log.w(TAG, "Cannot start Motion Sync: Route playback is actively running.")
            return
        }
        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        stopCurrentLoop()
        acquireWakeLock()
        isStopping.set(false)
        isSimulationPaused = false
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "MOTION_SYNC"
        sessionPrefs.lastLatitude = initialLat
        sessionPrefs.lastLongitude = initialLon
        currentSimLat = initialLat
        currentSimLon = initialLon
        currentSimAlt = 10.0
        currentSimSpeedMps = 0.0f
        currentSimBearing = 0.0f
        activeMode = SimulationMode.Fixed(initialLat, initialLon, 10.0)
        _serviceState.value = ServiceState.Running(
            mode = activeMode,
            latitude = initialLat,
            longitude = initialLon,
            altitude = 10.0,
            speedMps = 0f,
            bearingDegrees = 0f,
            isPaused = false
        )
        if (sessionPrefs.isSessionExpired || sessionPrefs.getTimeRemainingMillis() <= 0L) {
            sessionPrefs.startNewSession(SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS, forceRestart = true)
        }
        SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        // Sync WireGuard VPN Shield if user enabled auto-VPN sync and has active Pro
        val isPremMotion = com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value
        if (isPremMotion && settingsPrefs.isAutoVpnSyncEnabled && !com.fakegps.mocklocation.vpn.NowhereVpnService.isRunning) {
            val node = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(initialLat, initialLon)
            try {
                sessionPrefs.isIpMaskingEnabled = true
                com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, node.id)
            } catch (e: Exception) {
                Log.w(TAG, "VPN auto-sync start on motion sync failed (non-fatal): ${e.message}")
            }
        }

        startForegroundNotification("Motion Sync Active", "Syncing mock movement with physical sensors")
        motionSyncEngine?.start(initialLat, initialLon, 0f)
        com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationNameAsync(this, initialLat, initialLon) { resolvedName ->
            sessionPrefs.lastLocationName = resolvedName
            updateLocationNotification(initialLat, initialLon, "Motion Sync Active")
            updateAllWidgets()
        }
        startContinuousHeartbeatLoop()
        updateAllWidgets()
    }

    fun stopMotionSync() {
        motionSyncEngine?.stop()
        stopSpoofing()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: User swiped Nowhere from Recents. Maintaining active mock location in background.")
        if (sessionPrefs.isSessionActive) {
            acquireWakeLock()
            isStopping.set(false)

            // 1. Immediately re-anchor the foreground notification to maintain foreground priority
            val placeName = if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
                sessionPrefs.lastLocationName
            } else {
                String.format(Locale.US, "%.5f°, %.5f°", currentSimLat, currentSimLon)
            }
            startForegroundNotification(
                "📍 $placeName",
                "Nowhere Active • Running in background"
            )

            // 2. Ensure continuous heartbeat loop is actively pulsing GPS fixes
            if (simulationJob == null || !simulationJob!!.isActive) {
                startContinuousHeartbeatLoop()
            }
            // Do not alarm ourselves while service is actively running in foreground
        }
    }

    fun scheduleBackgroundRestartAlarm(delayMs: Long = 1000L) {
        if (!sessionPrefs.isSessionActive) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = android.os.SystemClock.elapsedRealtime() + delayMs

        // Primary: Broadcast to MockLocationServiceReceiver (officially supported on Android 12-15)
        val broadcastIntent = Intent(applicationContext, MockLocationServiceReceiver::class.java).apply {
            action = MockLocationServiceReceiver.ACTION_RESTORE_MOCK_SESSION
        }
        val broadcastPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            199,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        broadcastPendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        broadcastPendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    broadcastPendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    broadcastPendingIntent
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule restart alarm: ${e.message}")
        }
    }

    fun cancelBackgroundRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val broadcastIntent = Intent(applicationContext, MockLocationServiceReceiver::class.java).apply {
                action = MockLocationServiceReceiver.ACTION_RESTORE_MOCK_SESSION
            }
            val broadcastPendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                199,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(broadcastPendingIntent)
            Log.d(TAG, "Background restart alarm cancelled.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel restart alarm: ${e.message}")
        }
    }



    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nowhere::MockLocationLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
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

    private fun buildNotificationRemoteViews(
        locationName: String,
        coordsText: String,
        statusText: String,
        isRoute: Boolean,
        isPaused: Boolean,
        isDark: Boolean
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.widget_nowhere_layout)
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this)

        views.setTextViewText(R.id.tvWidgetLocationName, locationName)
        views.setTextViewText(R.id.tvWidgetCoords, coordsText)
        views.setTextViewText(R.id.tvWidgetStatus, statusText)

        views.setTextColor(R.id.tvWidgetBrandTitle, primaryColor)
        views.setInt(R.id.ivWidgetLogo, "setColorFilter", primaryColor)
        views.setInt(R.id.ivWidgetTeleportBg, "setColorFilter", primaryColor)

        // Dynamic Dark / Light theme styling matching Nowhere widget
        val bgGlassRes = if (isDark) R.drawable.bg_widget_glass_dark else R.drawable.bg_widget_glass_light
        val bgButtonRes = if (isDark) R.drawable.bg_widget_button_dark else R.drawable.bg_widget_button_light
        val primaryText = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val secondaryText = if (isDark) android.graphics.Color.parseColor("#AEAEB2") else android.graphics.Color.parseColor("#636366")

        views.setInt(R.id.widgetRoot, "setBackgroundResource", bgGlassRes)
        views.setInt(R.id.btnWidgetJoystick, "setBackgroundResource", bgButtonRes)
        views.setInt(R.id.btnWidgetOpenApp, "setBackgroundResource", bgButtonRes)

        views.setTextColor(R.id.tvWidgetLocationName, primaryText)
        views.setTextColor(R.id.tvWidgetCoords, secondaryText)
        views.setTextColor(R.id.tvWidgetStatus, primaryColor)
        views.setTextColor(R.id.btnWidgetJoystick, primaryColor)
        views.setInt(R.id.btnWidgetOpenApp, "setColorFilter", secondaryText)

        // Teleport / Stop button in notification
        views.setTextViewText(R.id.btnWidgetTeleport, "Stop")

        // Stop PendingIntent
        views.setOnClickPendingIntent(R.id.btnWidgetTeleport, getStopPendingIntent())

        // Secondary Action Button (Joystick or Route Pause/Resume)
        if (isRoute) {
            val actionTitle = if (isPaused) "Resume" else "Pause"
            views.setTextViewText(R.id.btnWidgetJoystick, actionTitle)
            views.setOnClickPendingIntent(R.id.btnWidgetJoystick, getPauseResumePendingIntent(isPaused))
        } else {
            views.setTextViewText(R.id.btnWidgetJoystick, "Joystick")
            views.setOnClickPendingIntent(R.id.btnWidgetJoystick, getJoystickPendingIntent())
        }

        // Open App PendingIntent
        val openAppPendingIntent = getOpenAppPendingIntent()
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.btnWidgetOpenApp, openAppPendingIntent)

        return views
    }

    private fun startForegroundNotification(locationTitle: String, contentSubtitle: String = "Location Injected & Active") {
        val stopPendingIntent = getStopPendingIntent()
        val openAppPendingIntent = getOpenAppPendingIntent()

        val remainingTimeStr = if (sessionPrefs.isPremiumActive()) "UNLIMITED" else sessionPrefs.formatRemainingTime()
        val formattedSubtitle = "⏱️ $remainingTimeStr left • $contentSubtitle"

        val isDark = when (settingsPrefs.appTheme) {
            "LIGHT" -> false
            "DARK" -> true
            else -> com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(this)
        }

        val remoteViews = buildNotificationRemoteViews(
            locationName = locationTitle,
            coordsText = formattedSubtitle,
            statusText = "● GPS LOCKED",
            isRoute = activeMode is SimulationMode.Route,
            isPaused = isSimulationPaused,
            isDark = isDark
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(locationTitle)
            .setContentText(formattedSubtitle)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                }
            }
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var started = false
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.i(TAG, "startForeground succeeded with LOCATION|SPECIAL_USE")
                started = true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with LOCATION|SPECIAL_USE failed: ${e.message}")
            }
            if (!started) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    Log.i(TAG, "startForeground succeeded with SPECIAL_USE")
                    started = true
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground with SPECIAL_USE failed: ${e.message}")
                }
            }
            if (!started) {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    Log.i(TAG, "startForeground succeeded with plain startForeground fallback")
                } catch (e: Exception) {
                    Log.e(TAG, "All startForeground attempts failed on API 34+: ${e.message}", e)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                Log.i(TAG, "startForeground succeeded with LOCATION")
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with LOCATION failed, falling back to plain: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Plain startForeground fallback failed: ${fallbackEx.message}", fallbackEx)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateLocationNotification(lat: Double, lon: Double, modeDescription: String = "Active") {
        try {
            val cachedName = com.fakegps.mocklocation.util.LocationNameResolver.getCachedLocationName(lat, lon)
            val placeName = if (!cachedName.isNullOrBlank()) {
                cachedName
            } else if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
                sessionPrefs.lastLocationName
            } else {
                // Trigger background resolution for future ticks
                serviceScope.launch(Dispatchers.IO) {
                    val resolved = com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationName(
                        this@MockLocationService,
                        lat,
                        lon
                    )
                    if (resolved.isNotBlank()) {
                        sessionPrefs.lastLocationName = resolved
                        updateLocationNotification(lat, lon, modeDescription)
                    }
                }
                String.format(Locale.US, "%.5f°, %.5f°", lat, lon)
            }

            val coordsText = String.format(Locale.US, "%.5f°, %.5f° • %s", lat, lon, modeDescription)

            val stopPendingIntent = getStopPendingIntent()
            val openAppPendingIntent = getOpenAppPendingIntent()

            val runningState = _serviceState.value as? ServiceState.Running
            val isRoute = activeMode is SimulationMode.Route && runningState != null && runningState.totalDistanceMeters > 0
            val isPaused = isSimulationPaused || (runningState?.isPaused == true)
            val progress = if (isRoute && runningState != null && runningState.totalDistanceMeters > 0) {
                ((runningState.distanceCoveredMeters / runningState.totalDistanceMeters) * 100).toInt().coerceIn(0, 100)
            } else 0

            val coveredStr = if (isRoute && runningState != null) String.format(Locale.US, "%.2f km", runningState.distanceCoveredMeters / 1000.0) else ""
            val totalStr = if (isRoute && runningState != null) String.format(Locale.US, "%.2f km", runningState.totalDistanceMeters / 1000.0) else ""
            val remainingStr = if (isRoute && runningState != null) String.format(Locale.US, "%.2f km", runningState.distanceRemainingMeters / 1000.0) else ""
            val speedKmh = if (runningState != null) runningState.speedMps * 3.6f else 0.0f
            val speedMps = runningState?.speedMps?.coerceAtLeast(0.1f) ?: 0.1f
            val etaSec = if (isRoute && runningState != null && speedMps > 0.3f && runningState.distanceRemainingMeters > 5.0) {
                (runningState.distanceRemainingMeters / speedMps).toLong()
            } else 0L
            val etaStr = if (isRoute) formatEta(etaSec) else ""

            val remainingTimeStr = if (sessionPrefs.isPremiumActive()) "UNLIMITED" else sessionPrefs.formatRemainingTime()

            val notifTitle = if (isRoute) {
                "📍 Route: $coveredStr / $totalStr ($progress%) • ⏱️ $remainingTimeStr"
            } else {
                "📍 $placeName • ⏱️ $remainingTimeStr"
            }

            val notifText = if (isRoute) {
                "⏱️ $remainingTimeStr left • ETA: $etaStr • Speed: ${speedKmh.toInt()} km/h"
            } else {
                "⏱️ $remainingTimeStr left • $coordsText"
            }

            val routeDetails = if (isRoute) {
                "\n📍 Route: $coveredStr / $totalStr ($progress%) • $remainingStr left\n⏱️ ETA: $etaStr • Speed: ${speedKmh.toInt()} km/h"
            } else ""

            val isDark = when (settingsPrefs.appTheme) {
                "LIGHT" -> false
                "DARK" -> true
                else -> com.fakegps.mocklocation.util.ThemeColorManager.isWidgetDarkMode(this@MockLocationService)
            }

            val baseStatus = when {
                isPaused -> "PAUSED"
                isRoute -> "ROUTE ($progress%)"
                activeMode is SimulationMode.Joystick -> "JOYSTICK"
                sessionPrefs.activeMode == "MOTION_SYNC" -> "MOTION SYNC"
                else -> "GPS LOCKED"
            }
            val statusText = "● $baseStatus"

            val remoteViews = buildNotificationRemoteViews(
                locationName = placeName,
                coordsText = "$coordsText • ⏱️ $remainingTimeStr left",
                statusText = statusText,
                isRoute = isRoute,
                isPaused = isPaused,
                isDark = isDark
            )

            val builder = NotificationCompat.Builder(this@MockLocationService, CHANNEL_ID)
                .setContentTitle(notifTitle)
                .setContentText(notifText)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this@MockLocationService))
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                    }
                }

            if (isRoute) {
                builder.setProgress(100, progress, false)
            }

            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(notifTitle)
                    .bigText("$coordsText$routeDetails\nGPS Mocking active in background across all apps.")
            )

            if (activeMode is SimulationMode.Route) {
                val pauseResumePendingIntent = getPauseResumePendingIntent(isPaused)
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

    fun scheduleWatchdog() {
        // Disabled: Recurring 60s alarms while foreground service is active trigger
        // ForegroundServiceStartNotAllowedException / RemoteServiceException crashes on Android 12-15.
        cancelWatchdog()
    }

    private fun cancelWatchdog() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val broadcastIntent = Intent(applicationContext, MockLocationServiceReceiver::class.java).apply {
                action = MockLocationServiceReceiver.ACTION_RESTORE_MOCK_SESSION
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                999,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Watchdog alarm cancelled.")
        } catch (ignored: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            try {
                manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            } catch (ignored: Exception) {}
            try {
                manager.deleteNotificationChannel(OLD_CHANNEL_ID_V2)
            } catch (ignored: Exception) {}
            try {
                manager.deleteNotificationChannel(OLD_CHANNEL_ID_V3)
            } catch (ignored: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nowhere Active GPS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent status indicator when Nowhere GPS simulation is actively running"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun startFixed(latitude: Double, longitude: Double, altitude: Double = 15.0, fromRestore: Boolean = false) {
        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        isStopping.set(false)
        isSimulationPaused = false
        stopCurrentLoop()
        acquireWakeLock()
        activeMode = SimulationMode.Fixed(latitude, longitude, altitude)
        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "FIXED"
        sessionPrefs.lastLatitude = latitude
        sessionPrefs.lastLongitude = longitude
        sessionPrefs.lastAltitude = altitude
        currentSimLat = latitude
        currentSimLon = longitude
        currentSimAlt = altitude
        currentSimSpeedMps = 0.0f
        currentSimBearing = 0.0f
        _serviceState.value = ServiceState.Running(
            mode = activeMode,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speedMps = 0.0f,
            bearingDegrees = 0.0f,
            isPaused = false
        )
        updateAllWidgets()

        if (!fromRestore) {
            if (sessionPrefs.isSessionExpired || sessionPrefs.getTimeRemainingMillis() <= 0L) {
                sessionPrefs.startNewSession(SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS, forceRestart = true)
            }
            SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)
        } else {
            SessionTimerManager.resumeExistingTimer(this)
        }

        // Sync WireGuard VPN Shield if user enabled auto-VPN sync and has active Pro
        val isPremFixed = com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value
        if (isPremFixed && settingsPrefs.isAutoVpnSyncEnabled && !com.fakegps.mocklocation.vpn.NowhereVpnService.isRunning) {
            val node = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(latitude, longitude)
            try {
                sessionPrefs.isIpMaskingEnabled = true
                com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, node.id)
            } catch (e: Exception) {
                Log.w(TAG, "VPN auto-sync start failed (non-fatal): ${e.message}")
            }
        }

        // Signal Kill Switch that mock GPS is active (arms protection, allows normal traffic)
        com.fakegps.mocklocation.vpn.KillSwitchManager.onMockLocationStarted(this)

        serviceScope.launch(Dispatchers.IO) {
            com.fakegps.mocklocation.weather.WeatherManager.fetchWeather(this@MockLocationService, latitude, longitude)
        }

        com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(latitude, longitude, altitude, 0.0f, 0.0f)
        val placeName = if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
            sessionPrefs.lastLocationName
        } else {
            com.fakegps.mocklocation.util.LocationNameResolver.getCachedLocationName(latitude, longitude) ?: String.format(Locale.US, "%.5f°, %.5f°", latitude, longitude)
        }
        startForegroundNotification(
            "📍 $placeName",
            String.format(Locale.US, "Fixed Location • %.5f°, %.5f°", latitude, longitude)
        )
        updateLocationNotification(latitude, longitude, "Teleported / Fixed")

        com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationNameAsync(this, latitude, longitude) { resolvedName ->
            sessionPrefs.lastLocationName = resolvedName
            updateLocationNotification(latitude, longitude, "Teleported / Fixed")
            updateAllWidgets()
        }

        startContinuousHeartbeatLoop()
    }

    private fun startContinuousHeartbeatLoop() {
        if (simulationJob?.isActive == true) return
        simulationJob = serviceScope.launch(hardwareTickDispatcher) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (ignored: Throwable) {}
            if (!engine.isEngineActive()) {
                engine.initialize()
            } else if (!engine.isGmsMockModeEngaged) {
                engine.ensureGmsMockMode(2000L)
            }
            var lastNotificationUpdateTime = 0L
            var lastWidgetUpdateTime = 0L
            while (isActive) {
                try {
                    if (!sessionPrefs.isPremiumActive()) {
                        val remaining = sessionPrefs.getTimeRemainingMillis()
                        if (remaining <= 0L) {
                            Log.i(TAG, "Continuous session duration expired in background (quota reached).")
                            sessionPrefs.isSessionExpired = true
                            sessionPrefs.isSessionRunning = false
                            SessionTimerManager.notifySessionExpired(this@MockLocationService)
                        }
                    }

                    val lat = currentSimLat
                    val lon = currentSimLon
                    val alt = currentSimAlt
                    val spd = currentSimSpeedMps
                    val brg = currentSimBearing

                    val result = engine.setLocation(
                        latitude = lat,
                        longitude = lon,
                        altitude = alt,
                        speed = spd,
                        bearing = brg,
                        applyStationaryJitter = settingsPrefs.randomizeJitter && spd == 0.0f
                    )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        Log.w(TAG, "Transient injection error: ${error?.message}, retrying...")
                        delay(250L)
                        continue
                    } else {
                        val loc = result.getOrNull()
                        if (loc != null) {
                            try {
                                com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(loc.latitude, loc.longitude, loc.altitude, spd, brg)
                            } catch (e: Exception) {}
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                altitude = loc.altitude,
                                speedMps = spd,
                                bearingDegrees = brg
                            )
                        }
                    }

                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastWidgetUpdateTime >= 15000L) {
                        lastWidgetUpdateTime = now
                        try { updateAllWidgets() } catch (e: Exception) {}
                    }
                    if (now - lastNotificationUpdateTime >= 5000L) {
                        lastNotificationUpdateTime = now
                        val modeDesc = if (sessionPrefs.activeMode == "MOTION_SYNC") "Motion Sync Active" else "Teleported / Fixed"
                        updateLocationNotification(lat, lon, modeDesc)
                    }

                    // Sub-second 500ms (2Hz) continuous GPS heartbeat cadence:
                    // Saturates Google Maps' internal location buffer so the fix is always fresh (< 600ms old).
                    // Permanently prevents Google Maps' Kalman filter from degrading into stale state ("ash/grey dot").
                    val loopDelay = if (spd > 0.05f) {
                        settingsPrefs.updateIntervalMovingMs.coerceIn(250L, 500L)
                    } else {
                        500L
                    }
                    delay(loopDelay)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error in fixed/motion simulation loop tick: ${t.message}", t)
                    delay(500L)
                }
            }
        }
    }

    fun startRoute(
        waypoints: List<RoutePoint>,
        speedKmh: Float = 20.0f,
        isLooping: Boolean = true,
        transportMode: TransportMode = TransportMode.VEHICLE,
        forceRestart: Boolean = false,
        fromRestore: Boolean = false,
        initialWaypointIndex: Int = 0
    ) {
        if (waypoints.size < 2) return

        val currentMode = activeMode
        if (!forceRestart && currentMode is SimulationMode.Route && currentMode.waypoints == waypoints && simulationJob?.isActive == true) {
            updateRouteSpeed(speedKmh)
            updateRouteLooping(isLooping)
            return
        }

        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        isStopping.set(false)
        isSimulationPaused = false
        stopCurrentLoop()
        acquireWakeLock()

        val simulator = RouteSimulator(waypoints, speedKmh, isLooping, transportMode)
        if (initialWaypointIndex > 0) {
            simulator.seekToWaypointIndex(initialWaypointIndex)
        }
        routeSimulator = simulator
        activeMode = SimulationMode.Route(waypoints, speedKmh, isLooping)
        motionSyncEngine?.setRoutePlaybackActive(true)

        sessionPrefs.isSessionActive = true
        sessionPrefs.activeMode = "ROUTE"
        sessionPrefs.lastSpeedKmh = speedKmh
        sessionPrefs.isLooping = isLooping
        sessionPrefs.lastTransportMode = transportMode.name
        sessionPrefs.lastRouteIndex = initialWaypointIndex
        sessionPrefs.saveWaypoints(waypoints)

        val firstPt = waypoints.first()
        _serviceState.value = ServiceState.Running(
            mode = activeMode,
            latitude = firstPt.latitude,
            longitude = firstPt.longitude,
            altitude = firstPt.altitude,
            speedMps = (speedKmh * 1000f / 3600f),
            bearingDegrees = 0f,
            isPaused = false
        )
        updateAllWidgets()

        if (!fromRestore) {
            if (sessionPrefs.isSessionExpired || sessionPrefs.getTimeRemainingMillis() <= 0L) {
                sessionPrefs.startNewSession(SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS, forceRestart = true)
            }
            SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)
        } else {
            SessionTimerManager.resumeExistingTimer(this)
        }

        // Sync WireGuard VPN Shield if user enabled auto-VPN sync and has active Pro
        val isPremRoute = com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value
        if (isPremRoute && settingsPrefs.isAutoVpnSyncEnabled && !com.fakegps.mocklocation.vpn.NowhereVpnService.isRunning) {
            val node = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(firstPt.latitude, firstPt.longitude)
            try {
                sessionPrefs.isIpMaskingEnabled = true
                com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, node.id)
            } catch (e: Exception) {
                Log.w(TAG, "VPN auto-sync start on route failed (non-fatal): ${e.message}")
            }
        }

        // Signal Kill Switch that mock GPS is active (arms protection, allows normal traffic)
        com.fakegps.mocklocation.vpn.KillSwitchManager.onMockLocationStarted(this)

        val totalRouteDist = simulator.totalDistanceMeters
        sendRouteStartedNotification(waypoints.size, totalRouteDist, speedKmh, transportMode.title)

        startForegroundNotification(
            String.format("Route: %d waypoints", waypoints.size),
            String.format("%.1f KM/H • %s", speedKmh, transportMode.title)
        )
        if (waypoints.isNotEmpty()) {
            updateLocationNotification(waypoints[0].latitude, waypoints[0].longitude, "Route Active (${transportMode.title})")
            com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationNameAsync(this, waypoints[0].latitude, waypoints[0].longitude) { resolvedName ->
                sessionPrefs.lastLocationName = resolvedName
                updateLocationNotification(waypoints[0].latitude, waypoints[0].longitude, "Route Active (${transportMode.title})")
                updateAllWidgets()
            }
        }
        updateAllWidgets()

        simulationJob = serviceScope.launch(hardwareTickDispatcher) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (ignored: Throwable) {}
            if (!engine.isEngineActive()) {
                engine.initialize()
            } else if (!engine.isGmsMockModeEngaged) {
                engine.ensureGmsMockMode(2000L)
            }
            var lastTickTime = android.os.SystemClock.elapsedRealtime()
            var lastWidgetUpdateTime = 0L
            var lastNotificationUpdateTime = 0L
            var lastWeatherUpdateTime = 0L
            var lastWeatherLat = 0.0
            var lastWeatherLon = 0.0

            while (isActive) {
                try {
                    if (!sessionPrefs.isPremiumActive()) {
                        val remaining = sessionPrefs.getTimeRemainingMillis()
                        if (remaining <= 0L) {
                            Log.i(TAG, "Route session duration expired in background (quota reached).")
                            sessionPrefs.isSessionExpired = true
                            sessionPrefs.isSessionRunning = false
                            SessionTimerManager.notifySessionExpired(this@MockLocationService)
                        }
                    }

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
                            try {
                                com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(simLoc.latitude, simLoc.longitude, simLoc.altitude, simLoc.speedMps, simLoc.bearingDegrees)
                            } catch (e: Exception) {}
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
                            sessionPrefs.lastRouteIndex = simulator.currentSegmentIndex
                            sessionPrefs.routeTotalDistanceMeters = simLoc.totalDistanceMeters
                            sessionPrefs.routeCoveredDistanceMeters = simLoc.distanceCoveredMeters
                            sessionPrefs.routeRemainingDistanceMeters = simLoc.distanceRemainingMeters

                            // Periodic home screen widget update in background (every 5s)
                            if (now - lastWidgetUpdateTime >= 5000L) {
                                lastWidgetUpdateTime = now
                                try { updateAllWidgets() } catch (e: Exception) {}
                            }

                            // Real-time foreground notification progress update in background (every 2s)
                            if (now - lastNotificationUpdateTime >= 2000L) {
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
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (ignored: Exception) {}
                                }
                            }
                        }

                        if (simLoc.isCompleted) {
                            Log.i(TAG, "Route completed: automatically transitioning to Fixed mock location at destination: (${simLoc.latitude}, ${simLoc.longitude})")
                            sendRouteCompletedNotification(simLoc.totalDistanceMeters, simLoc.latitude, simLoc.longitude)
                            try { updateAllWidgets() } catch (e: Exception) {}
                            startFixed(simLoc.latitude, simLoc.longitude, simLoc.altitude)
                            break
                        }
                    }
                    delay(200L) // Ultra-smooth 200ms route physics & continuous anti-dropout anchor pulse (5Hz)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error in route simulation tick: ${t.message}", t)
                    delay(200L)
                }
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
                .setContentTitle("Route Simulation Active")
                .setContentText("$waypointsCount waypoints • $transportMode • $distStr • Speed: ${speedKmh.toInt()} km/h • ETA: $etaStr")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
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
                .setContentTitle("Destination Reached")
                .setContentText("$coordsStr • Total distance: $distStr")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(this))
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
        isSimulationPaused = true
        routeSimulator?.pause()
        val current = _serviceState.value
        if (current is ServiceState.Running) {
            _serviceState.value = current.copy(isPaused = true)
        }
        SessionTimerManager.pauseTimer(this)
        updateAllWidgets()
    }

    fun resumeRoute() {
        isSimulationPaused = false
        routeSimulator?.resume()
        val current = _serviceState.value
        if (current is ServiceState.Running) {
            _serviceState.value = current.copy(isPaused = false)
        }
        SessionTimerManager.startOrResumeTimer(this)
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

    fun startJoystick(startLat: Double, startLon: Double, speedKmh: Float = 10.0f, fromRestore: Boolean = false) {
        activeInstance = this
        MockLocationServiceReceiver.activeService = this
        isStopping.set(false)
        isSimulationPaused = false
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

        _serviceState.value = ServiceState.Running(
            mode = activeMode,
            latitude = startLat,
            longitude = startLon,
            altitude = 15.0,
            speedMps = 0.0f,
            bearingDegrees = 0.0f,
            isPaused = false
        )
        updateAllWidgets()

        if (!fromRestore) {
            if (sessionPrefs.isSessionExpired || sessionPrefs.getTimeRemainingMillis() <= 0L) {
                sessionPrefs.startNewSession(SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS, forceRestart = true)
            }
            SessionTimerManager.startOrResumeTimer(this, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)
        } else {
            SessionTimerManager.resumeExistingTimer(this)
        }

        // Sync WireGuard VPN Shield if user enabled auto-VPN sync and has active Pro
        val isPremJoystick = com.fakegps.mocklocation.billing.BillingManager.getInstance(this).isPremium.value
        if (isPremJoystick && settingsPrefs.isAutoVpnSyncEnabled && !com.fakegps.mocklocation.vpn.NowhereVpnService.isRunning) {
            val node = com.fakegps.mocklocation.vpn.IpManager.findClosestNodeForCoordinates(startLat, startLon)
            try {
                sessionPrefs.isIpMaskingEnabled = true
                com.fakegps.mocklocation.vpn.NowhereVpnService.start(this, node.id)
            } catch (e: Exception) {
                Log.w(TAG, "VPN auto-sync start on joystick failed (non-fatal): ${e.message}")
            }
        }

        // Signal Kill Switch that mock GPS is active (arms protection, allows normal traffic)
        com.fakegps.mocklocation.vpn.KillSwitchManager.onMockLocationStarted(this)

        val placeName = if (sessionPrefs.lastLocationName.isNotBlank() && sessionPrefs.lastLocationName != "Mock Location Active") {
            sessionPrefs.lastLocationName
        } else {
            com.fakegps.mocklocation.util.LocationNameResolver.getCachedLocationName(startLat, startLon) ?: String.format(Locale.US, "%.5f°, %.5f°", startLat, startLon)
        }
        startForegroundNotification(
            "📍 $placeName",
            String.format("Joystick Active • %.5f°, %.5f° • %.1f KM/H", startLat, startLon, speedKmh)
        )
        updateLocationNotification(startLat, startLon, "Joystick Active")

        com.fakegps.mocklocation.util.LocationNameResolver.resolveLocationNameAsync(this, startLat, startLon) { resolvedName ->
            sessionPrefs.lastLocationName = resolvedName
            updateLocationNotification(startLat, startLon, "Joystick Active")
            updateAllWidgets()
        }

        simulationJob = serviceScope.launch(hardwareTickDispatcher) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (ignored: Throwable) {}
            if (!engine.isEngineActive()) {
                engine.initialize()
            } else if (!engine.isGmsMockModeEngaged) {
                engine.ensureGmsMockMode(2000L)
            }
            val deltaSeconds = 0.1
            var lastWidgetUpdateTime = 0L
            var lastNotificationUpdateTime = 0L

            while (isActive) {
                try {
                    if (!sessionPrefs.isPremiumActive()) {
                        val remaining = sessionPrefs.getTimeRemainingMillis()
                        if (remaining <= 0L) {
                            Log.i(TAG, "Joystick session duration expired in background (quota reached).")
                            sessionPrefs.isSessionExpired = true
                            sessionPrefs.isSessionRunning = false
                            SessionTimerManager.notifySessionExpired(this@MockLocationService)
                        }
                    }

                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastWidgetUpdateTime >= 5000L) {
                        lastWidgetUpdateTime = now
                        try { updateAllWidgets() } catch (e: Exception) {}
                    }

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
                            try {
                                com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(newLat, newLon, 15.0, speedMps, joystickAngleDeg)
                            } catch (e: Exception) {}
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = newLat,
                                longitude = newLon,
                                altitude = 15.0,
                                speedMps = speedMps,
                                bearingDegrees = joystickAngleDeg
                            )

                            // Real-time notification update during joystick movement (every 2s)
                            if (now - lastNotificationUpdateTime >= 2000L) {
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
                            try {
                                com.fakegps.mocklocation.hotspot.HotspotLocationServer.updateLocation(joystickLat, joystickLon, 15.0, 0.0f, joystickAngleDeg)
                            } catch (e: Exception) {}
                            _serviceState.value = ServiceState.Running(
                                mode = activeMode,
                                latitude = joystickLat,
                                longitude = joystickLon,
                                altitude = 15.0,
                                speedMps = 0.0f,
                                bearingDegrees = joystickAngleDeg
                            )

                            // Periodic notification update when joystick is stationary (every 5s)
                            if (now - lastNotificationUpdateTime >= 5000L) {
                                lastNotificationUpdateTime = now
                                updateLocationNotification(joystickLat, joystickLon, "Joystick Active (Stationary)")
                            }
                        }
                    }

                    delay(100L)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error in joystick simulation loop tick: ${t.message}", t)
                    delay(100L)
                }
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
        Log.i(TAG, "stopSpoofing called by user. Terminating simulation and releasing all resources.")
        com.fakegps.mocklocation.util.RecentsShieldManager.ensureVisibleInRecents(this)
        cancelWatchdog()
        cancelBackgroundRestartAlarm()
        stopCurrentLoop()
        releaseWakeLock()
        try { engine.stop() } catch (e: Exception) { Log.w(TAG, "engine.stop() error (non-fatal): ${e.message}") }
        sessionPrefs.isSessionActive = false
        SessionTimerManager.stopTimer(this)
        isSimulationPaused = false
        activeMode = SimulationMode.Idle
        _serviceState.value = ServiceState.Idle
        try { updateAllWidgets() } catch (e: Exception) { Log.w(TAG, "widget update on stop (non-fatal): ${e.message}") }
        try { FloatingJoystickService.stop(this) } catch (e: Exception) {}
        if (settingsPrefs.isAutoVpnSyncEnabled) {
            try { com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this) } catch (e: Exception) {}
        }
        try { com.fakegps.mocklocation.vpn.KillSwitchManager.onMockLocationStopped(this, "Mock GPS stopped by user") } catch (e: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {}
        stopSelf()
    }

    private fun stopCurrentLoop() {
        simulationJob?.cancel()
        simulationJob = null
        routeSimulator = null
        motionSyncEngine?.setRoutePlaybackActive(false)
    }

    private fun restoreActiveSession() {
        if (!sessionPrefs.isSessionActive) return
        // If simulation is already running (e.g. watchdog fired while active), skip restore
        if (simulationJob?.isActive == true) {
            Log.d(TAG, "restoreActiveSession: simulation already active, skipping.")
            return
        }
        isStopping.set(false)
        acquireWakeLock()
        SessionTimerManager.resumeExistingTimer(this)

        when (sessionPrefs.activeMode) {
            "FIXED" -> {
                startFixed(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude,
                    sessionPrefs.lastAltitude,
                    fromRestore = true
                )
            }
            "ROUTE" -> {
                val waypoints = sessionPrefs.getWaypoints()
                val savedMode = try {
                    TransportMode.valueOf(sessionPrefs.lastTransportMode)
                } catch (e: Exception) {
                    TransportMode.VEHICLE
                }
                val savedIndex = sessionPrefs.lastRouteIndex
                if (waypoints.size >= 2) {
                    startRoute(
                        waypoints = waypoints,
                        speedKmh = sessionPrefs.lastSpeedKmh,
                        isLooping = sessionPrefs.isLooping,
                        transportMode = savedMode,
                        fromRestore = true,
                        initialWaypointIndex = savedIndex
                    )
                } else {
                    startFixed(
                        sessionPrefs.lastLatitude,
                        sessionPrefs.lastLongitude,
                        sessionPrefs.lastAltitude,
                        fromRestore = true
                    )
                }
            }
            "JOYSTICK" -> {
                startJoystick(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude,
                    sessionPrefs.lastSpeedKmh,
                    fromRestore = true
                )
            }
            "MOTION_SYNC" -> {
                startMotionSync(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude
                )
            }
            else -> {
                startFixed(
                    sessionPrefs.lastLatitude,
                    sessionPrefs.lastLongitude,
                    sessionPrefs.lastAltitude,
                    fromRestore = true
                )
            }
        }

        // Retry loop for initial location injection on restore (ensures immediate blue dot)
        serviceScope.launch {
            var attempts = 0
            while (attempts < 5 && isActive) {
                val res = engine.setLocation(
                    latitude = sessionPrefs.lastLatitude,
                    longitude = sessionPrefs.lastLongitude,
                    altitude = sessionPrefs.lastAltitude,
                    speed = 0.0f,
                    bearing = 0.0f,
                    applyStationaryJitter = false
                )
                if (res.isSuccess) {
                    Log.i(TAG, "Initial mock location successfully injected on restore.")
                    break
                }
                attempts++
                delay(300L)
            }
        }
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        if (MockLocationServiceReceiver.activeService == this) MockLocationServiceReceiver.activeService = null

        val userStopped = isStopping.get() // set true only by stopSpoofing()
        if (userStopped) {
            sessionPrefs.isSessionActive = false
            SessionTimerManager.stopTimer(this)
            cancelBackgroundRestartAlarm()
        } else if (sessionPrefs.isSessionActive) {
            // System-initiated destroy (OS / OEM kill): keep session active, schedule alarm to bring us back
            scheduleBackgroundRestartAlarm(1000L)
        }

        activeMode = SimulationMode.Idle
        _serviceState.value = ServiceState.Idle

        isSimulationPaused = false
        cancelWatchdog()
        stopCurrentLoop()
        releaseWakeLock()
        try { wifiTriggerHandler?.stop(); wifiTriggerHandler = null } catch (e: Exception) {}
        try { motionSyncEngine?.stop(); motionSyncEngine = null } catch (e: Exception) {}
        try { com.fakegps.mocklocation.hotspot.HotspotLocationServer.stopServer() } catch (e: Exception) {}
        if (userStopped && settingsPrefs.isAutoVpnSyncEnabled) {
            try { com.fakegps.mocklocation.vpn.NowhereVpnService.stop(this) } catch (e: Exception) {}
        }
        if (userStopped) {
            try { com.fakegps.mocklocation.vpn.KillSwitchManager.onMockLocationStopped(this, "Mock GPS service destroyed") } catch (e: Exception) {}
            try { engine.stop() } catch (e: Exception) {}
        }
        try { hardwareTickExecutor.shutdownNow() } catch (ignored: Throwable) {}
        serviceJob.cancel()
        super.onDestroy()
    }
}
