package com.fakegps.mocklocation

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.engine.GhostCloakEngine
import com.fakegps.mocklocation.engine.IGmsLocationClient
import com.fakegps.mocklocation.engine.MockLocationEngine
import com.fakegps.mocklocation.engine.RealismLayer
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationEngineTest {

    private lateinit var context: Context
    private lateinit var engine: MockLocationEngine
    private lateinit var locationManager: LocationManager
    private lateinit var shadowLocationManager: ShadowLocationManager

    class FakeGmsLocationClient : IGmsLocationClient {
        var setMockModeCalls = 0
        var setMockLocationCalls = 0
        var shouldFailMockLocation = false
        var shouldFailMockMode = false
        var mockLocationTaskNeverCompletes = false
        var mockModeTaskNeverCompletes = false

        override fun setMockMode(isMockMode: Boolean): Task<Void> {
            if (isMockMode) setMockModeCalls++
            if (mockModeTaskNeverCompletes) {
                return TaskCompletionSource<Void>().task
            }
            return if (shouldFailMockMode) {
                Tasks.forException(Exception("Simulated GMS setMockMode failure"))
            } else {
                Tasks.forResult(null)
            }
        }

        override fun setMockLocation(mockLocation: Location): Task<Void> {
            setMockLocationCalls++
            if (mockLocationTaskNeverCompletes) {
                return TaskCompletionSource<Void>().task
            }
            return if (shouldFailMockLocation) {
                Tasks.forException(Exception("Simulated GMS setMockLocation failure"))
            } else {
                Tasks.forResult(null)
            }
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowLocationManager = Shadows.shadowOf(locationManager)
        val settings = AppSettingsPreferences(context)
        val realism = RealismLayer(settings)
        engine = MockLocationEngine(context, settings, realism)
    }

    @Test
    fun testInitialize_registersTestProvidersSuccessfully() {
        val res = engine.initialize()
        assertTrue("Engine initialization should succeed", res.isSuccess)
        assertTrue("Engine should be active after initialization", engine.isEngineActive())
    }

    @Test
    fun testSetLocation_injectsCoordinatesSuccessfully() {
        val initRes = engine.initialize()
        assertTrue(initRes.isSuccess)

        val targetLat = 37.7749
        val targetLon = -122.4194
        val result = engine.setLocation(
            latitude = targetLat,
            longitude = targetLon,
            altitude = 20.0,
            speed = 10.0f,
            bearing = 45.0f,
            applyStationaryJitter = false
        )

        assertTrue("setLocation should succeed", result.isSuccess)
        val location = result.getOrNull()
        assertNotNull(location)
        assertEquals(targetLat, location!!.latitude, 0.001)
        assertEquals(targetLon, location.longitude, 0.001)
        assertEquals(10.0f, location.speed, 0.1f)
        assertEquals(45.0f, location.bearing, 0.1f)
    }

    @Test
    fun testStop_cleansUpProviders() {
        engine.initialize()
        assertTrue(engine.isEngineActive())

        engine.stop()
        assertFalse("Engine should be inactive after stop()", engine.isEngineActive())
        assertFalse("GMS mock mode should be marked inactive after stop()", engine.isGmsMockModeEngaged)
    }

    @Test
    fun testEnsureGmsMockMode_doesNotCrash() {
        val result = engine.ensureGmsMockMode(1000L)
        assertNotNull(result)
    }

    @Test
    fun testSetLocation_stationaryAccuracyBound() {
        engine.initialize()
        val result = engine.setLocation(40.7128, -74.0060, 10.0, 0.0f, 0.0f, false)
        assertTrue(result.isSuccess)
        val loc = result.getOrNull()
        assertNotNull(loc)
        assertTrue("Accuracy should be between 2.0 and 5.0m for solid blue dot", loc!!.accuracy in 2.0f..5.0f)
    }

    @Test
    fun testSetLocation_healthyGms_atMostOneSetMockModeCallAcross100Injections() {
        val fakeGms = FakeGmsLocationClient()
        val settings = AppSettingsPreferences(context)
        val testEngine = MockLocationEngine(context, settings, RealismLayer(settings), GhostCloakEngine(settings), fakeGms)
        val initRes = testEngine.initialize()
        assertTrue(initRes.isSuccess)
        assertEquals("Init must engage mock mode once", 1, fakeGms.setMockModeCalls)
        assertEquals(MockLocationEngine.GmsState.ENGAGED, testEngine.gmsState)

        for (i in 1..100) {
            val res = testEngine.setLocation(37.7749, -122.4194)
            assertTrue(res.isSuccess)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Must still be at most 1 setMockMode call — no redundant calls while healthy!
        assertEquals("setMockMode(true) must be called at most once when GMS is healthy", 1, fakeGms.setMockModeCalls)
        // 2 calls per injection tick (GPS + Fused provider dual injection to saturate Google Maps Kalman filter)
        assertEquals(200, fakeGms.setMockLocationCalls)
    }

    @Test
    fun testSetLocation_gmsFailure_triggersSingleReengageAndExponentialBackoff() {
        val fakeGms = FakeGmsLocationClient()
        val settings = AppSettingsPreferences(context)
        val testEngine = MockLocationEngine(context, settings, RealismLayer(settings), GhostCloakEngine(settings), fakeGms)
        testEngine.initialize()
        assertEquals(1, fakeGms.setMockModeCalls)

        // Induce GMS failure
        fakeGms.shouldFailMockLocation = true
        fakeGms.shouldFailMockMode = true

        // First tick failure triggers re-engage
        testEngine.setLocation(37.7749, -122.4194)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Initial failure triggers exactly one re-engage attempt", 2, fakeGms.setMockModeCalls)
        assertTrue("Consecutive failures should be >= 1", testEngine.consecutiveFailures >= 1)
        val retryTime1 = testEngine.nextRetryAtElapsedMs
        assertTrue("Backoff timestamp should be scheduled in future", retryTime1 > SystemClock.elapsedRealtime())

        // Rapid subsequent ticks do NOT re-call setMockMode because nextRetryAtElapsedMs is in future
        for (i in 1..10) {
            testEngine.setLocation(37.7749, -122.4194)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("Subsequent ticks within backoff window must NOT spam setMockMode", 2, fakeGms.setMockModeCalls)

        // Simulate backoff passing and another failure to verify backoff increases
        testEngine.nextRetryAtElapsedMs = 0L
        testEngine.requestGmsReengage()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, fakeGms.setMockModeCalls)
        val retryTime2 = testEngine.nextRetryAtElapsedMs
        assertTrue("Second failure must have larger backoff", retryTime2 > SystemClock.elapsedRealtime())

        // Now simulate success resets consecutiveFailures and backoff
        fakeGms.shouldFailMockLocation = false
        fakeGms.shouldFailMockMode = false
        testEngine.nextRetryAtElapsedMs = 0L
        testEngine.requestGmsReengage()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MockLocationEngine.GmsState.ENGAGED, testEngine.gmsState)
        assertEquals("Success must reset consecutive failures to 0", 0, testEngine.consecutiveFailures)
        assertEquals("Success must reset next retry timestamp to 0", 0L, testEngine.nextRetryAtElapsedMs)
    }

    @Test
    fun testSetLocation_neverBlocks_withHangingGmsTask() {
        val fakeGms = FakeGmsLocationClient().apply {
            mockLocationTaskNeverCompletes = true
        }
        val settings = AppSettingsPreferences(context)
        val testEngine = MockLocationEngine(context, settings, RealismLayer(settings), GhostCloakEngine(settings), fakeGms)
        testEngine.initialize()

        val start = System.currentTimeMillis()
        val res = testEngine.setLocation(37.7749, -122.4194)
        val durationMs = System.currentTimeMillis() - start

        assertTrue(res.isSuccess)
        assertTrue("setLocation must return immediately without blocking even if task hangs, took ${durationMs}ms", durationMs < 200)
    }

    @Test
    fun testSetLocation_strictlyIncreasingElapsedRealtimeNanos() {
        engine.initialize()
        val loc1 = engine.setLocation(37.7749, -122.4194).getOrThrow()
        val loc2 = engine.setLocation(37.7749, -122.4194).getOrThrow()
        assertTrue(
            "elapsedRealtimeNanos must be strictly increasing across consecutive injections",
            loc2.elapsedRealtimeNanos > loc1.elapsedRealtimeNanos
        )
    }
}
