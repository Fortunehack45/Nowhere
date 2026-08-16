package com.fakegps.mocklocation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.service.ServiceState
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.simulator.TransportMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationServiceTest {

    private lateinit var controller: ServiceController<MockLocationService>
    private lateinit var service: MockLocationService
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(MockLocationService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        try {
            service.stopSpoofing()
            controller.destroy()
        } catch (ignored: Exception) {}
    }

    @Test
    fun testService_startFixedAction_acquiresWakeLockAndSetsRunningState() {
        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_FIXED
            putExtra(MockLocationService.EXTRA_LATITUDE, 37.7749)
            putExtra(MockLocationService.EXTRA_LONGITUDE, -122.4194)
            putExtra(MockLocationService.EXTRA_ALTITUDE, 25.0)
        }

        service.onStartCommand(intent, 0, 1)

        assertTrue("WakeLock should be held during active simulation", service.isWakeLockHeld())
        val sessionPrefs = SessionPreferences(context)
        assertTrue("Session should be active", sessionPrefs.isSessionActive)
        assertEquals("FIXED", sessionPrefs.activeMode)
        assertEquals(37.7749, sessionPrefs.lastLatitude, 0.001)
        assertEquals(-122.4194, sessionPrefs.lastLongitude, 0.001)
    }

    @Test
    fun testService_startRouteAction_runsRouteSimulation() {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.saveWaypoints(
            listOf(
                RoutePoint(37.7749, -122.4194),
                RoutePoint(37.7759, -122.4194)
            )
        )

        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_SPEED_KMH, 40.0f)
            putExtra(MockLocationService.EXTRA_IS_LOOPING, true)
            putExtra(MockLocationService.EXTRA_TRANSPORT_MODE, TransportMode.VEHICLE.name)
        }

        service.onStartCommand(intent, 0, 1)

        assertTrue("WakeLock should be held for route", service.isWakeLockHeld())
        assertTrue("Session should be active", sessionPrefs.isSessionActive)
        assertEquals("ROUTE", sessionPrefs.activeMode)
        assertEquals(40.0f, sessionPrefs.lastSpeedKmh, 0.1f)
    }

    @Test
    fun testService_pauseAndResumeRoute() {
        val sessionPrefs = SessionPreferences(context)
        sessionPrefs.saveWaypoints(
            listOf(
                RoutePoint(37.7749, -122.4194),
                RoutePoint(37.7759, -122.4194)
            )
        )

        val startIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
        }
        service.onStartCommand(startIntent, 0, 1)

        // Pause
        val pauseIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_PAUSE_ROUTE
        }
        service.onStartCommand(pauseIntent, 0, 2)

        // Resume
        val resumeIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_RESUME_ROUTE
        }
        service.onStartCommand(resumeIntent, 0, 3)

        assertTrue(service.isWakeLockHeld())
    }

    @Test
    fun testService_startJoystickAction() {
        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_JOYSTICK
            putExtra(MockLocationService.EXTRA_LATITUDE, 40.7128)
            putExtra(MockLocationService.EXTRA_LONGITUDE, -74.0060)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, 15.0f)
        }

        service.onStartCommand(intent, 0, 1)

        assertTrue("WakeLock should be held for joystick", service.isWakeLockHeld())
        val sessionPrefs = SessionPreferences(context)
        assertTrue("Session should be active", sessionPrefs.isSessionActive)
        assertEquals("JOYSTICK", sessionPrefs.activeMode)
    }

    @Test
    fun testService_stopAction_releasesWakeLockAndSetsIdle() {
        val startIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_FIXED
            putExtra(MockLocationService.EXTRA_LATITUDE, 37.7749)
            putExtra(MockLocationService.EXTRA_LONGITUDE, -122.4194)
        }
        service.onStartCommand(startIntent, 0, 1)
        assertTrue(service.isWakeLockHeld())

        val stopIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        service.onStartCommand(stopIntent, 0, 2)

        assertFalse("WakeLock should be released after stop", service.isWakeLockHeld())
        assertEquals("Service state should be Idle", ServiceState.Idle, service.serviceState.value)
        val sessionPrefs = SessionPreferences(context)
        assertFalse("Session active flag should be false", sessionPrefs.isSessionActive)
    }

    @Test
    fun testService_restoreSessionAction() {
        val sessionPrefs = SessionPreferences(context).apply {
            isSessionActive = true
            activeMode = "FIXED"
            lastLatitude = 51.5074
            lastLongitude = -0.1278
            lastAltitude = 10.0
        }

        val restoreIntent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_RESTORE_SESSION
        }
        service.onStartCommand(restoreIntent, 0, 1)

        assertTrue("Restored session should hold WakeLock", service.isWakeLockHeld())
    }
}
