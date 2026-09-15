package com.fakegps.mocklocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.vpn.KillSwitchManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KillSwitchManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SessionPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = SessionPreferences(context)
        prefs.isKillSwitchEnabled = false
        prefs.isKillSwitchBypassed = false
        prefs.isSessionActive = false
        KillSwitchManager.evaluate(context)
    }

    @After
    fun tearDown() {
        prefs.isKillSwitchEnabled = false
        prefs.isKillSwitchBypassed = false
        prefs.isSessionActive = false
        KillSwitchManager.evaluate(context)
    }

    @Test
    fun testKillSwitch_defaultDisabled() {
        KillSwitchManager.evaluate(context)
        assertTrue(KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Disabled)
    }

    @Test
    fun testKillSwitch_armsWhenMockLocationActive() {
        prefs.isKillSwitchEnabled = true
        prefs.isSessionActive = true

        KillSwitchManager.evaluate(context)

        assertTrue(
            "Status should be Armed when mock location is active and Kill Switch enabled",
            KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Armed
        )
    }

    @Test
    fun testKillSwitch_triggersWhenMockLocationInactive() {
        prefs.isKillSwitchEnabled = true
        prefs.isSessionActive = false

        KillSwitchManager.evaluate(context)

        val status = KillSwitchManager.status.value
        assertTrue(
            "Status should be Triggered when mock location is inactive and Kill Switch enabled",
            status is KillSwitchManager.KillSwitchStatus.Triggered
        )
    }

    @Test
    fun testKillSwitch_lifecycleHooksTransitionCorrectly() {
        KillSwitchManager.setEnabled(context, true)

        // Simulate mock location start
        prefs.isSessionActive = true
        KillSwitchManager.onMockLocationStarted(context)
        assertTrue(KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Armed)

        // Simulate mock location stop
        prefs.isSessionActive = false
        KillSwitchManager.onMockLocationStopped(context, "User stopped spoofing")
        val stoppedStatus = KillSwitchManager.status.value
        assertTrue(stoppedStatus is KillSwitchManager.KillSwitchStatus.Triggered)
        assertEquals("User stopped spoofing", (stoppedStatus as KillSwitchManager.KillSwitchStatus.Triggered).reason)
    }

    @Test
    fun testKillSwitch_emergencyBypassAllowsTraffic() {
        KillSwitchManager.setEnabled(context, true)
        prefs.isSessionActive = false
        KillSwitchManager.evaluate(context)
        assertTrue(KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Triggered)

        KillSwitchManager.setBypassed(context, true)
        assertTrue(KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Bypassed)
        assertTrue(prefs.isKillSwitchBypassed)

        // Starting mock location should auto-reset bypass and arm shield
        prefs.isSessionActive = true
        KillSwitchManager.onMockLocationStarted(context)
        assertTrue(KillSwitchManager.status.value is KillSwitchManager.KillSwitchStatus.Armed)
        assertFalse("Bypass should be cleared on new simulation start", prefs.isKillSwitchBypassed)
    }
}
