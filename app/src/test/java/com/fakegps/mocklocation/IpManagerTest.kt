package com.fakegps.mocklocation

import com.fakegps.mocklocation.vpn.IpManager
import org.junit.Assert.*
import org.junit.Test

class IpManagerTest {

    @Test
    fun testGlobalPrivacyNodes_populated() {
        val nodes = IpManager.GLOBAL_PRIVACY_NODES
        assertTrue("Global privacy nodes list should not be empty", nodes.isNotEmpty())
        assertTrue("Expected real global privacy nodes, actual: ${nodes.size}", nodes.size >= 10)

        for (node in nodes) {
            assertNotNull(node.id)
            assertNotNull(node.virtualIp)
            assertNotNull(node.country)
            assertNotNull(node.city)
            assertTrue(node.pingMs > 0)
        }
    }

    @Test
    fun testGetNodeById() {
        val nodeUk = IpManager.getNodeById("uk_lon_1")
        assertEquals("United Kingdom", nodeUk.country)
        assertEquals("London", nodeUk.city)

        val defaultNode = IpManager.getNodeById("invalid_id")
        assertEquals("us_central_gcp", defaultNode.id)
    }

    @Test
    fun testFindClosestNodeForCoordinates() {
        // Tokyo coordinates (35.6762, 139.6503) -> Japan node
        val tokyoMatch = IpManager.findClosestNodeForCoordinates(35.6895, 139.6917)
        assertEquals("jp_tyo_1", tokyoMatch.id)
        assertEquals("Japan", tokyoMatch.country)

        // London coordinates (51.5074, -0.1278) -> UK node
        val londonMatch = IpManager.findClosestNodeForCoordinates(51.5072, -0.1276)
        assertEquals("uk_lon_1", londonMatch.id)
        assertEquals("United Kingdom", londonMatch.country)

        // New York coordinates (40.7128, -74.0060) -> US East node
        val nycMatch = IpManager.findClosestNodeForCoordinates(40.7580, -73.9855)
        assertEquals("us_nyc_1", nycMatch.id)
    }

    @Test
    fun testSearchFiltering() {
        val allNodes = IpManager.GLOBAL_PRIVACY_NODES
        val adapter = com.fakegps.mocklocation.ui.dialogs.IpNodeAdapter(allNodes, "us_central_gcp") {}

        val countFrance = adapter.filter("France")
        assertTrue("Expected at least 1 node for France, actual: $countFrance", countFrance >= 1)

        val countTokyo = adapter.filter("Tokyo")
        assertEquals(1, countTokyo)

        val countUs = adapter.filter("United States")
        assertTrue(countUs >= 3)

        val countNone = adapter.filter("NonExistentCountryXYZ")
        assertEquals(0, countNone)

        val countAll = adapter.filter("")
        assertEquals(allNodes.size, countAll)
    }
}
