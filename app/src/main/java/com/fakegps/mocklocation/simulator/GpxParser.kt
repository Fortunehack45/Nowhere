package com.fakegps.mocklocation.simulator

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Portable, high-performance parser for standard GPX (GPS Exchange Format) XML files.
 * Compatible with both Android runtime and standard JVM unit tests.
 */
object GpxParser {

    fun parse(inputStream: InputStream): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(inputStream)
            document.documentElement.normalize()

            val targetTags = listOf("trkpt", "rtept", "wpt")
            for (tag in targetTags) {
                val nodes = document.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val element = node as Element
                        val latStr = element.getAttribute("lat")
                        val lonStr = element.getAttribute("lon")
                        val lat = latStr.toDoubleOrNull()
                        val lon = lonStr.toDoubleOrNull()

                        if (lat != null && lon != null) {
                            var ele = 0.0
                            val eleNodes = element.getElementsByTagName("ele")
                            if (eleNodes.length > 0) {
                                ele = eleNodes.item(0).textContent?.trim()?.toDoubleOrNull() ?: 0.0
                            }
                            points.add(RoutePoint(latitude = lat, longitude = lon, altitude = ele))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Return empty list on parse failure
        }
        return points
    }

    fun parse(xmlContent: String): List<RoutePoint> {
        return parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
    }
}
