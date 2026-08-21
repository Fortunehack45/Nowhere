package com.fakegps.mocklocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.SessionTimerManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTimerManagerTest {

    private lateinit var context: Context
    private lateinit var sessionPrefs: SessionPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sessionPrefs = SessionPreferences(context)
        sessionPrefs.resetSessionForTesting()
    }

    @Test
    fun testStartNewSession_createsCorrectExpiry() {
        val duration = 60 * 60 * 1000L // 1 hour
        val before = System.currentTimeMillis()
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val after = System.currentTimeMillis()

        assertTrue(sessionPrefs.isSessionActive)
        assertFalse(sessionPrefs.isSessionExpired)
        assertEquals(duration, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.sessionExpiresTimestamp >= before + duration)
        assertTrue(sessionPrefs.sessionExpiresTimestamp <= after + duration)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testStartNewSession_doesNotRestartDurationAcrossAppUpdate() {
        // Step 1: User starts 2-hour session
        val twoHours = 2 * 60 * 60 * 1000L
        sessionPrefs.startNewSession(twoHours, forceRestart = true)
        val originalExpiry = sessionPrefs.sessionExpiresTimestamp

        // Step 2: Simulate app update or process recreation calling startOrResumeTimer or startNewSession
        sessionPrefs.startNewSession(twoHours, forceRestart = false)

        // Step 3: Verify expiry timestamp was NOT reset/restarted
        assertEquals("Expiry timestamp should remain untouched across app updates/restarts", originalExpiry, sessionPrefs.sessionExpiresTimestamp)
        assertEquals(twoHours, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testStartOrResumeTimer_resumesExistingSessionWithoutResetting() {
        val duration = 90 * 60 * 1000L // 90 min
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val originalExpiry = sessionPrefs.sessionExpiresTimestamp

        // Simulate app reopening after APK update
        SessionTimerManager.startOrResumeTimer(context, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        assertEquals("Expiry timestamp should remain the original unexpired timestamp", originalExpiry, sessionPrefs.sessionExpiresTimestamp)
        assertEquals(duration, sessionPrefs.sessionAllocatedDurationMillis)
    }

    @Test
    fun testStopSimulation_preservesRemainingDurationAndValidSession() {
        // User starts simulation with 1 hour duration
        val oneHour = 60 * 60 * 1000L
        sessionPrefs.startNewSession(oneHour, forceRestart = true)
        val originalExpiry = sessionPrefs.sessionExpiresTimestamp

        // User stops simulation while they still have time remaining
        SessionTimerManager.stopTimer(context)

        // Verify remaining session is STILL valid and NOT expired
        assertTrue("Session must remain valid when stopped if time remains", sessionPrefs.hasValidActiveSession())
        assertFalse("Session must not be marked expired when stopped manually", sessionPrefs.isSessionExpired)
        assertEquals("Expiry timestamp must be preserved", originalExpiry, sessionPrefs.sessionExpiresTimestamp)
        assertTrue("Remaining time must be positive", sessionPrefs.getTimeRemainingMillis() > 0L)

        // User restarts simulation: timer resumes without prompting for ads or resetting
        SessionTimerManager.startOrResumeTimer(context)
        assertEquals("Expiry timestamp must still match original after restart", originalExpiry, sessionPrefs.sessionExpiresTimestamp)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testExtendSession_addsDurationProperly() {
        val baseDuration = 30 * 60 * 1000L // 30 min
        sessionPrefs.startNewSession(baseDuration, forceRestart = true)
        val initialExpiry = sessionPrefs.sessionExpiresTimestamp

        val extraMillis = SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS // 2 hr extra
        sessionPrefs.extendSession(extraMillis)

        assertEquals(initialExpiry + extraMillis, sessionPrefs.sessionExpiresTimestamp)
        assertEquals(baseDuration + extraMillis, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testFormatAllocatedDuration_formatsCorrectly() {
        val duration = 2 * 60 * 60 * 1000L // 2 hours
        sessionPrefs.startNewSession(duration, forceRestart = true)
        assertEquals("2h 00m", sessionPrefs.formatAllocatedDuration())
    }
}
