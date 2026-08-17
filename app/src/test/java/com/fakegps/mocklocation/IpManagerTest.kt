package com.fakegps.mocklocation

import com.fakegps.mocklocation.vpn.IpManager
import org.junit.Assert.*
import org.junit.Test

class IpManagerTest {

    @Test
    fun testGlobalPrivacyNodes_populated() {
        val nodes = IpManager.GLOBAL_PRIVACY_NODES
        assertTrue("Global privacy nodes list should not be empty", nodes.isNotEmpty())
        assertEquals(10, nodes.size)

        for (node in nodes) {
            assertNotNull(node.id)
            assertNotNull(node.virtualIp)
            assertNotNull(node.country)
            assertTrue(node.pingMs > 0)
        }
    }

    @Test
    fun testGetNodeById() {
        val nodeUk = IpManager.getNodeById("uk_lon")
        assertEquals("United Kingdom", nodeUk.country)
        assertEquals("London", nodeUk.city)

        val defaultNode = IpManager.getNodeById("invalid_id")
        assertEquals("us_nyc", defaultNode.id)
    }

    @Test
    fun testFindClosestNodeForCoordinates() {
        // Tokyo coordinates (35.6762, 139.6503) -> Japan node
        val tokyoMatch = IpManager.findClosestNodeForCoordinates(35.6895, 139.6917)
        assertEquals("jp_tyo", tokyoMatch.id)
        assertEquals("Japan", tokyoMatch.country)

        // London coordinates (51.5074, -0.1278) -> UK node
        val londonMatch = IpManager.findClosestNodeForCoordinates(51.5072, -0.1276)
        assertEquals("uk_lon", londonMatch.id)
        assertEquals("United Kingdom", londonMatch.country)

        // New York coordinates (40.7128, -74.0060) -> US East node
        val nycMatch = IpManager.findClosestNodeForCoordinates(40.7580, -73.9855)
        assertEquals("us_nyc", nycMatch.id)
    }
}
