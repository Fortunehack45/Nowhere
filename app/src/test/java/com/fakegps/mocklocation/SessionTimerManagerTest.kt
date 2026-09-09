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
        val remainingBefore = sessionPrefs.getTimeRemainingMillis()

        sessionPrefs.startNewSession(twoHours, forceRestart = false)

        assertEquals(twoHours, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue(sessionPrefs.hasValidActiveSession())
        val remainingAfter = sessionPrefs.getTimeRemainingMillis()
        assertTrue(
            "Remaining quota must stay near the leftover, not reset to a fresh 2h",
            remainingAfter in (remainingBefore - 5_000L)..(remainingBefore + 2_000L)
        )
    }

    @Test
    fun testStartOrResumeTimer_resumesExistingSessionWithoutResettingQuota() {
        val duration = 90 * 60 * 1000L // 90 min
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val remainingBefore = sessionPrefs.getTimeRemainingMillis()

        SessionTimerManager.startOrResumeTimer(context, SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS)

        assertEquals(duration, sessionPrefs.sessionAllocatedDurationMillis)
        val remainingAfter = sessionPrefs.getTimeRemainingMillis()
        assertTrue(remainingAfter in (remainingBefore - 5_000L)..(remainingBefore + 2_000L))
        assertTrue(sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testStopSimulation_pausesClockAndKeepsLeftoverQuota() {
        val oneHour = 60 * 60 * 1000L
        sessionPrefs.startNewSession(oneHour, forceRestart = true)
        val remainingBeforeStop = sessionPrefs.getTimeRemainingMillis()

        SessionTimerManager.stopTimer(context)

        assertFalse(
            "Disconnected sessions are not 'active' — time must not keep ticking",
            sessionPrefs.hasValidActiveSession()
        )
        assertTrue(sessionPrefs.hasRemainingQuota())
        assertTrue(sessionPrefs.isTimerPaused)
        assertFalse(sessionPrefs.isSessionExpired)
        assertFalse(sessionPrefs.isSessionActive)
        val remainingWhilePaused = sessionPrefs.getTimeRemainingMillis()
        assertTrue(remainingWhilePaused > 0L)
        assertTrue(remainingWhilePaused <= remainingBeforeStop)

        Thread.sleep(80L)
        assertEquals(
            "Paused leftover must not drain while disconnected",
            remainingWhilePaused,
            sessionPrefs.getTimeRemainingMillis()
        )

        SessionTimerManager.startOrResumeTimer(context)
        assertTrue(sessionPrefs.hasValidActiveSession())
        val remainingAfterResume = sessionPrefs.getTimeRemainingMillis()
        assertTrue(
            remainingAfterResume in (remainingWhilePaused - 5_000L)..(remainingWhilePaused + 2_000L)
        )
        assertTrue(
            "Must resume leftover, not a fresh 2-hour grant",
            remainingAfterResume < SessionPreferences.DEFAULT_SESSION_DURATION_MILLIS - 30_000L
        )
    }

    @Test
    fun testPauseTimer_doesNotExpireQuota() {
        sessionPrefs.startNewSession(45 * 60 * 1000L, forceRestart = true)
        SessionTimerManager.pauseTimer(context)

        assertTrue(sessionPrefs.isTimerPaused)
        assertFalse(sessionPrefs.isSessionExpired)
        assertTrue(sessionPrefs.hasRemainingQuota())
        assertFalse(sessionPrefs.hasValidActiveSession())
        assertEquals(
            SessionTimerManager.timerState.value.isPaused,
            true
        )
    }

    @Test
    fun testExtendSession_addsDurationProperly() {
        val baseDuration = 30 * 60 * 1000L // 30 min
        sessionPrefs.startNewSession(baseDuration, forceRestart = true)
        val remainingBefore = sessionPrefs.getTimeRemainingMillis()

        val extraMillis = SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS // 2 hr extra
        sessionPrefs.extendSession(extraMillis)

        val remainingAfter = sessionPrefs.getTimeRemainingMillis()
        assertTrue(remainingAfter >= remainingBefore + extraMillis - 3_000L)
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
