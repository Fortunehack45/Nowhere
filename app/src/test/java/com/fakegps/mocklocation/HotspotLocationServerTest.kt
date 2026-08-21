package com.fakegps.mocklocation

import com.fakegps.mocklocation.hotspot.HotspotLocationServer
import com.fakegps.mocklocation.util.QrCodeGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotLocationServerTest {

    @Test
    fun testNmea0183SentenceGeneration() {
        val lat = 37.774929
        val lon = -122.419416
        val alt = 15.0
        val speedMps = 10.0f
        val bearing = 90.0f

        val sentences = HotspotLocationServer.generateNmea0183Sentences(lat, lon, alt, speedMps, bearing)

        assertEquals(3, sentences.size)

        val rmc = sentences[0]
        val gga = sentences[1]
        val vtg = sentences[2]

        // Verify valid NMEA headers
        assertTrue("RMC sentence should start with \$GPRMC: $rmc", rmc.startsWith("\$GPRMC"))
        assertTrue("GGA sentence should start with \$GPGGA: $gga", gga.startsWith("\$GPGGA"))
        assertTrue("VTG sentence should start with \$GPVTG: $vtg", vtg.startsWith("\$GPVTG"))

        // Verify checksums exist (*XX at end)
        assertTrue(rmc.contains("*"))
        assertTrue(gga.contains("*"))
        assertTrue(vtg.contains("*"))

        // Verify coordinate content
        assertTrue(rmc.contains("N") || rmc.contains("S"))
        assertTrue(rmc.contains("E") || rmc.contains("W"))
        assertTrue(gga.contains("15.0"))
    }

    @Test
    fun testLocationUpdate() {
        HotspotLocationServer.updateLocation(40.7128, -74.0060, 25.0, 5.0f, 180.0f)
        val sentences = HotspotLocationServer.generateNmea0183Sentences(40.7128, -74.0060, 25.0, 5.0f, 180.0f)

        assertNotNull(sentences)
        assertTrue(sentences[1].contains("25.0"))
    }

    @Test
    fun testQrCodeGenerator() {
        val bitmap = QrCodeGenerator.generateQrBitmap("http://192.168.43.1:8088", 200, 200)
        assertNotNull("Generated QR bitmap should not be null", bitmap)
        assertEquals(200, bitmap?.width)
        assertEquals(200, bitmap?.height)
    }

    @Test
    fun testQrCodeGenerator_withEmbeddedLogo() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = QrCodeGenerator.generateQrBitmap("http://192.168.43.1:8088", 300, 300, context = context)
        assertNotNull("Generated QR bitmap with logo should not be null", bitmap)
        assertEquals(300, bitmap?.width)
        assertEquals(300, bitmap?.height)
    }

    @Test
    fun testIpResolution_returnsValidIpv4() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ip = HotspotLocationServer.getHotspotOrWifiIpAddress(context)
        assertNotNull("Resolved IP must not be null", ip)
        assertTrue("Resolved IP must be a valid IPv4 string", ip.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\$")))

        val allIps = HotspotLocationServer.getAllLocalIpAddresses(context)
        assertTrue("All IPs list must contain at least one valid IP", allIps.isNotEmpty())
        for (addr in allIps) {
            assertTrue("Every address must match IPv4 format", addr.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\$")))
        }
    }
}
