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
        val duration = 60 * 60 * 1000L
        val before = System.currentTimeMillis()
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val after = System.currentTimeMillis()

        assertTrue(sessionPrefs.isSessionActive)
        assertFalse(sessionPrefs.isTimerPaused)
        assertFalse(sessionPrefs.isSessionExpired)
        assertEquals(duration, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.sessionExpiresTimestamp >= before + duration)
        assertTrue(sessionPrefs.sessionExpiresTimestamp <= after + duration)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testStartNewSession_doesNotRestartAllocatedDurationAcrossReconnect() {
        val twoHours = 2 * 60 * 60 * 1000L
        sessionPrefs.startNewSession(twoHours, forceRestart = true)
        sessionPrefs.startNewSession(twoHours, forceRestart = false)

        assertEquals(twoHours, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
        assertFalse(sessionPrefs.isTimerPaused)
    }

    @Test
    fun testStartOrResumeTimer_resumesExistingSessionWithoutResettingQuota() {
        val duration = 90 * 60 * 1000L
        sessionPrefs.startNewSession(duration, forceRestart = true)
        SessionTimerManager.startOrResumeTimer(context, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        assertEquals(duration, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testStopSimulation_pausesClockAndKeepsLeftoverQuota() {
        val oneHour = 60 * 60 * 1000L
        sessionPrefs.startNewSession(oneHour, forceRestart = true)

        SessionTimerManager.stopTimer(context)

        assertFalse(sessionPrefs.hasValidActiveSession())
        assertTrue(sessionPrefs.hasRemainingQuota())
        assertTrue(sessionPrefs.isTimerPaused)
        assertFalse(sessionPrefs.isSessionExpired)
        assertFalse(sessionPrefs.isSessionActive)
        assertTrue(sessionPrefs.getTimeRemainingMillis() > 0L)

        SessionTimerManager.startOrResumeTimer(context)
        assertTrue(sessionPrefs.hasValidActiveSession())
        assertFalse(sessionPrefs.isTimerPaused)
        assertTrue(sessionPrefs.getTimeRemainingMillis() > 0L)
    }

    @Test
    fun testPauseTimer_doesNotExpireQuota() {
        sessionPrefs.startNewSession(45 * 60 * 1000L, forceRestart = true)
        SessionTimerManager.pauseTimer(context)

        assertTrue(sessionPrefs.isTimerPaused)
        assertFalse(sessionPrefs.isSessionExpired)
        assertTrue(sessionPrefs.hasRemainingQuota())
        assertFalse(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testExtendSession_addsDurationProperly() {
        val baseDuration = 30 * 60 * 1000L
        sessionPrefs.startNewSession(baseDuration, forceRestart = true)
        sessionPrefs.extendSession(SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)

        assertEquals(baseDuration + SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testFormatAllocatedDuration_formatsCorrectly() {
        val duration = 2 * 60 * 60 * 1000L
        sessionPrefs.startNewSession(duration, forceRestart = true)
        assertEquals("2h 00m", sessionPrefs.formatAllocatedDuration())
    }
}
