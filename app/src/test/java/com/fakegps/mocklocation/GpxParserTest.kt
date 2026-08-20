package com.fakegps.mocklocation

import com.fakegps.mocklocation.simulator.GpxParser
import org.junit.Assert.*
import org.junit.Test

class GpxParserTest {

    @Test
    fun testParseGpx_trackPoints() {
        val sampleGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Test">
                <trk>
                    <name>Sample Route</name>
                    <trkseg>
                        <trkpt lat="37.7749" lon="-122.4194">
                            <ele>15.5</ele>
                            <time>2026-08-14T20:00:00Z</time>
                        </trkpt>
                        <trkpt lat="37.7750" lon="-122.4190">
                            <ele>16.2</ele>
                            <time>2026-08-14T20:00:10Z</time>
                        </trkpt>
                        <trkpt lat="37.7760" lon="-122.4180">
                            <ele>18.0</ele>
                        </trkpt>
                    </trkseg>
                </trk>
            </gpx>
        """.trimIndent()

        val points = GpxParser.parse(sampleGpx)
        assertEquals(3, points.size)

        assertEquals(37.7749, points[0].latitude, 0.00001)
        assertEquals(-122.4194, points[0].longitude, 0.00001)
        assertEquals(15.5, points[0].altitude, 0.01)

        assertEquals(37.7760, points[2].latitude, 0.00001)
        assertEquals(18.0, points[2].altitude, 0.01)
    }

    @Test
    fun testParseGpx_emptyOrInvalid() {
        val invalidGpx = "<gpx><trk></trk></gpx>"
        val points = GpxParser.parse(invalidGpx)
        assertTrue(points.isEmpty())
    }

    @Test
    fun testGpxExport_roundtrip() {
        val originalPoints = listOf(
            com.fakegps.mocklocation.simulator.RoutePoint(37.7749, -122.4194, altitude = 15.0, stopDurationSeconds = 30),
            com.fakegps.mocklocation.simulator.RoutePoint(37.7759, -122.4184, altitude = 20.0, stopDurationSeconds = 0),
            com.fakegps.mocklocation.simulator.RoutePoint(37.7769, -122.4174, altitude = 25.0, stopDurationSeconds = 60)
        )

        val gpxXml = com.fakegps.mocklocation.simulator.GpxExporter.exportToGpx(originalPoints, "Test Export Route")
        assertNotNull(gpxXml)
        assertTrue(gpxXml.contains("<gpx"))
        assertTrue(gpxXml.contains("Test Export Route"))
        assertTrue(gpxXml.contains("37.7749"))

        val parsedPoints = GpxParser.parse(gpxXml)
        assertTrue("Parsed points should not be empty", parsedPoints.isNotEmpty())
        assertEquals(37.7749, parsedPoints[0].latitude, 0.0001)
        assertEquals(-122.4194, parsedPoints[0].longitude, 0.0001)
    }
}
