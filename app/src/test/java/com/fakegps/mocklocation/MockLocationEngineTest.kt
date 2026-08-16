package com.fakegps.mocklocation

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.engine.MockLocationEngine
import com.fakegps.mocklocation.engine.RealismLayer
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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowLocationManager = Shadows.shadowOf(locationManager)
        val settings = AppSettingsPreferences(context)
        val realism = RealismLayer(settings)
        engine = MockLocationEngine(context, realism, settings)
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
    }
}
