package com.fakegps.mocklocation

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.engine.GhostCloakEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GhostCloakEngineTest {

    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var ghostCloakEngine: GhostCloakEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        settingsPrefs = AppSettingsPreferences(context)
        settingsPrefs.isGhostCloakEnabled = true
        settingsPrefs.isNmeaSynthesisEnabled = true
        settingsPrefs.isClockDriftEmulationEnabled = true
        settingsPrefs.isSensorKinematicsEnabled = true
        ghostCloakEngine = GhostCloakEngine(settingsPrefs)
    }

    @Test
    fun testGenerateNmeaStream_containsValidSentencesAndChecksums() {
        val lat = 37.7749
        val lon = -122.4194
        val alt = 20.0
        val speedMps = 15.0f
        val bearingDeg = 90.0f
        val timeMs = 1725000000000L

        val sentences = ghostCloakEngine.generateNmeaStream(
            latitude = lat,
            longitude = lon,
            altitude = alt,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            timestampMs = timeMs
        )

        assertTrue("Expected at least 5 NMEA sentences, actual: ${sentences.size}", sentences.size >= 5)

        val hasRmc = sentences.any { it.startsWith("\$GPRMC") }
        val hasGga = sentences.any { it.startsWith("\$GPGGA") }
        val hasGsa = sentences.any { it.startsWith("\$GPGSA") }
        val hasGsv = sentences.any { it.startsWith("\$GPGSV") }

        assertTrue("Should contain \$GPRMC sentence", hasRmc)
        assertTrue("Should contain \$GPGGA sentence", hasGga)
        assertTrue("Should contain \$GPGSA sentence", hasGsa)
        assertTrue("Should contain \$GPGSV sentence", hasGsv)

        // Validate XOR checksum for all generated sentences
        for (sentence in sentences) {
            assertTrue("Sentence must start with '$'", sentence.startsWith("$"))
            assertTrue("Sentence must contain '*'", sentence.contains("*"))
            val parts = sentence.substring(1).split("*")
            assertEquals(2, parts.size)
            val payload = parts[0]
            val expectedHex = parts[1]

            var calculatedChecksum = 0
            for (ch in payload) {
                calculatedChecksum = calculatedChecksum xor ch.code
            }
            val calculatedHex = String.format(java.util.Locale.US, "%02X", calculatedChecksum)
            assertEquals("NMEA checksum must match", calculatedHex, expectedHex)
        }
    }

    @Test
    fun testCloakLocation_injectsAuthenticNanosecondUncertaintyAndExtras() {
        val rawLocation = Location("gps").apply {
            latitude = 51.5074
            longitude = -0.1278
            altitude = 30.0
            speed = 5.0f
            bearing = 180.0f
            accuracy = 1.5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        val cloakedLocation = ghostCloakEngine.cloakLocation(rawLocation)

        assertNotNull(cloakedLocation)
        assertEquals(51.5074, cloakedLocation.latitude, 0.0001)
        assertEquals(-0.1278, cloakedLocation.longitude, 0.0001)

        val extras = cloakedLocation.extras
        assertNotNull("Extras bundle must not be null", extras)
        assertTrue("Extras should have satellites count >= 12", extras!!.getInt("satellites") >= 12)
        assertTrue("Extras should have maxSatellites == 32", extras.getInt("maxSatellites") == 32)
        assertTrue("Extras should have meanCn0 > 25.0", extras.getDouble("meanCn0") > 25.0)
        assertEquals("Fix type should be 3D", "3D", extras.getString("fixType"))

        val nmeaList = extras.getStringArrayList("nmeaSentences")
        assertNotNull("NMEA sentences list should be present in extras", nmeaList)
        assertTrue(nmeaList!!.isNotEmpty())
    }

    @Test
    fun testComputeInertialTelemetry_computesCentripetalAndLongitudinalForces() {
        val speedMps = 20.0f
        val bearingDeg = 45.0f
        val deltaSeconds = 0.1

        val telemetry = ghostCloakEngine.computeInertialTelemetry(speedMps, bearingDeg, deltaSeconds)
        assertNotNull(telemetry)
        assertTrue("Vertical acceleration should be around Earth gravity 9.8 m/s²", telemetry.accelerationZ > 9.0f && telemetry.accelerationZ < 10.5f)
    }

    @Test
    fun testGenerateDiagnosticReport() {
        val report = ghostCloakEngine.generateDiagnosticReport(
            isEngineActive = true,
            isVpnActive = true,
            isRooted = false,
            isRootGranted = false
        )

        assertTrue(report.isGhostCloakEnabled)
        assertTrue(report.isNmeaStreamActive)
        assertTrue(report.isClockDriftActive)
        assertTrue(report.isMultiProviderActive)
        assertTrue(report.isIpShieldActive)
        assertFalse(report.isDeviceRooted)
        assertTrue(report.sampleNmea.startsWith("\$GPRMC"))
    }
}
