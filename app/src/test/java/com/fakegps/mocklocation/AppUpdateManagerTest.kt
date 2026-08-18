package com.fakegps.mocklocation

import com.fakegps.mocklocation.util.AppUpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testIsNewerVersion_patchIncrement() {
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.9", "1.0.10"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.10", "1.0.9"))
    }

    @Test
    fun testIsNewerVersion_minorAndMajorIncrement() {
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.5.2", "2.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun testIsNewerVersion_withVPrefix() {
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "v1.0.2"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "v1.0.3"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.4"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.5", "v1.0.5"))
    }

    @Test
    fun testIsNewerVersion_sameOrLower() {
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.2.3", "1.2.3"))
        assertFalse(AppUpdateManager.isNewerVersion("1.2.3", "1.2.2"))
    }

    @Test
    fun testIsNewerVersion_blankOrInvalid() {
        assertFalse(AppUpdateManager.isNewerVersion("", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", ""))
        assertFalse(AppUpdateManager.isNewerVersion("", ""))
    }
}
