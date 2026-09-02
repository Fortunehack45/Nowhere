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
        // Since only us_central_gcp is currently active (others upcoming), available routing matches us_central_gcp
        val tokyoMatch = IpManager.findClosestNodeForCoordinates(35.6895, 139.6917)
        assertEquals("us_central_gcp", tokyoMatch.id)
        assertTrue(tokyoMatch.isAvailable)

        val londonMatch = IpManager.findClosestNodeForCoordinates(51.5072, -0.1276)
        assertEquals("us_central_gcp", londonMatch.id)
        assertTrue(londonMatch.isAvailable)
    }

    @Test
    fun testSearchFiltering() {
        val allNodes = IpManager.GLOBAL_PRIVACY_NODES
        val adapter = com.fakegps.mocklocation.ui.dialogs.IpNodeAdapter(allNodes, "us_central_gcp") {}

        val countTokyo = adapter.filter("Tokyo")
        assertEquals(1, countTokyo)

        val countUs = adapter.filter("United States")
        assertTrue(countUs >= 1)

        val countNone = adapter.filter("NonExistentCountryXYZ")
        assertEquals(0, countNone)

        val countAll = adapter.filter("")
        assertEquals(allNodes.size, countAll)
    }
}
