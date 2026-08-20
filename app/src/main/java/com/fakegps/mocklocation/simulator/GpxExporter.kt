package com.fakegps.mocklocation.simulator

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Standard GPX (GPS Exchange Format 1.1) Exporter.
 * Serializes route waypoints and tracks into compliant XML for export and sharing.
 */
object GpxExporter {

    fun exportToGpx(
        waypoints: List<RoutePoint>,
        routeName: String = "Nowhere Route"
    ): String {
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"Nowhere Mock Location\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        builder.append("  <metadata>\n")
        builder.append("    <name>").append(escapeXml(routeName)).append("</name>\n")
        builder.append("    <time>").append(nowIso).append("</time>\n")
        builder.append("  </metadata>\n")

        // 1. Waypoints (<wpt>)
        for ((idx, pt) in waypoints.withIndex()) {
            builder.append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">\n", pt.latitude, pt.longitude))
            if (pt.altitude > 0.1) {
                builder.append(String.format(Locale.US, "    <ele>%.2f</ele>\n", pt.altitude))
            }
            builder.append("    <name>Waypoint ").append(idx + 1).append("</name>\n")
            if (pt.stopDurationSeconds > 0) {
                builder.append("    <cmt>Dwell: ").append(pt.stopDurationSeconds).append("s</cmt>\n")
            }
            builder.append("  </wpt>\n")
        }

        // 2. Track (<trk><trkseg><trkpt>)
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escapeXml(routeName)).append("</name>\n")
        builder.append("    <trkseg>\n")
        for (pt in waypoints) {
            builder.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n", pt.latitude, pt.longitude))
            if (pt.altitude > 0.1) {
                builder.append(String.format(Locale.US, "        <ele>%.2f</ele>\n", pt.altitude))
            }
            builder.append("      </trkpt>\n")
        }
        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>")

        return builder.toString()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
