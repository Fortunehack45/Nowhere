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

    @Test
    fun testTimerDoesNotCountDownWhenDisconnected() {
        val duration = 45 * 60 * 1000L // 45 min
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val initialRemaining = sessionPrefs.getTimeRemainingMillis()

        // When disconnected (stopTimer called)
        SessionTimerManager.stopTimer(context)

        // Simulate passage of time while disconnected
        Thread.sleep(100)

        val preservedRemaining = sessionPrefs.getTimeRemainingMillis()
        assertEquals("Timer must preserve exact remaining quota when disconnected", initialRemaining, preservedRemaining)
        assertTrue("Session must remain valid", sessionPrefs.hasValidActiveSession())
    }

    @Test
    fun testPauseTimer_preservesSessionActiveAndDuration() {
        val duration = 60 * 60 * 1000L // 60 min
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val initialRemaining = sessionPrefs.getTimeRemainingMillis()

        // When paused (e.g. during route pause)
        SessionTimerManager.pauseTimer(context)

        // isSessionActive should still be true, session not expired
        assertTrue(sessionPrefs.isSessionActive)
        assertFalse(sessionPrefs.isSessionExpired)
        assertEquals(initialRemaining, sessionPrefs.getTimeRemainingMillis())
        assertFalse("timerState isRunning must be false when paused", SessionTimerManager.timerState.value.isRunning)
    }

    @Test
    fun testUpdateStaticState_updatesTimerStateWithoutKillingSession() {
        val duration = 60 * 60 * 1000L // 60 min
        sessionPrefs.startNewSession(duration, forceRestart = true)
        val initialRemaining = sessionPrefs.getTimeRemainingMillis()

        // When navigating to settings or idling
        SessionTimerManager.updateStaticState(context)

        // Session must remain valid and not expired
        assertTrue(sessionPrefs.hasValidActiveSession())
        assertFalse(sessionPrefs.isSessionExpired)
        assertEquals(initialRemaining, sessionPrefs.getTimeRemainingMillis())
        assertFalse(SessionTimerManager.timerState.value.isRunning)
        assertEquals(sessionPrefs.formatRemainingTime(), SessionTimerManager.timerState.value.formattedRemaining)
    }

    @Test
    fun testStartStopAndRestartSimulation_preservesAndResumesTimer() {
        val twoHours = 2 * 60 * 60 * 1000L
        // 1. First start
        SessionTimerManager.startTimer(context, twoHours, forceRestart = true)
        assertTrue(sessionPrefs.isSessionActive)
        assertTrue(sessionPrefs.hasValidActiveSession())
        assertFalse(sessionPrefs.isSessionExpired)
        assertTrue(sessionPrefs.getTimeRemainingMillis() in (twoHours - 5000L)..twoHours)

        // 2. User stops simulation
        SessionTimerManager.stopTimer(context)
        val remainingAfterStop = sessionPrefs.getTimeRemainingMillis()
        assertFalse(sessionPrefs.isSessionActive)
        assertFalse(SessionTimerManager.timerState.value.isRunning)
        assertTrue("Session must remain valid for resume after stop", sessionPrefs.hasValidActiveSession())
        assertTrue("Remaining time must be positive", remainingAfterStop > 0L)

        // Simulate pause time while disconnected
        Thread.sleep(100)
        assertEquals("Remaining time must be preserved after stop", remainingAfterStop, sessionPrefs.getTimeRemainingMillis())

        // 3. User starts simulation again
        SessionTimerManager.startOrResumeTimer(context)
        assertTrue("Session must become active again upon restart", sessionPrefs.isSessionActive)
        assertFalse(SessionTimerManager.timerState.value.isExpired)
        assertTrue(sessionPrefs.hasValidActiveSession())
        assertEquals("Allocated duration must be preserved across restarts", twoHours, sessionPrefs.sessionAllocatedDurationMillis)
        assertTrue("Remaining duration must match preserved quota", sessionPrefs.getTimeRemainingMillis() in (remainingAfterStop - 5000L)..remainingAfterStop)
    }

    @Test
    fun testProcessDeathSimulation_preservesExactQuotaAndRestoresActiveSession() {
        val twoHours = 2 * 60 * 60 * 1000L
        SessionTimerManager.startTimer(context, twoHours, forceRestart = true)
        assertTrue(sessionPrefs.isSessionActive)

        // Simulate Android OS memory kill / swipe from recents:
        // onDestroy() pauses timer but preserves isSessionActive = true
        SessionTimerManager.pauseTimer(context)
        sessionPrefs.isSessionActive = true
        val savedQuotaAtDeath = sessionPrefs.sessionRemainingDurationMillis

        // Simulate passage of time while process is dead / offline
        Thread.sleep(150)
        assertEquals("Quota must NOT decrease while the process is dead", savedQuotaAtDeath, sessionPrefs.getTimeRemainingMillis())
        assertTrue("Session must remain active for system auto-restore", sessionPrefs.isSessionActive)

        // Simulate AlarmManager / START_STICKY restarting service & restoring session
        SessionTimerManager.resumeExistingTimer(context)
        assertTrue("Session must be active after restore", sessionPrefs.isSessionActive)
        assertFalse("Session must not be expired", sessionPrefs.isSessionExpired)
        assertEquals("Restored quota must match exact frozen quota", savedQuotaAtDeath, sessionPrefs.sessionRemainingDurationMillis)
        assertTrue("Remaining millis must match frozen quota", sessionPrefs.getTimeRemainingMillis() in (savedQuotaAtDeath - 5000L)..savedQuotaAtDeath)
    }

    @Test
    fun testPauseResumeWithLongDisconnectWait_preservesQuotaExactly() {
        val duration = 30 * 60 * 1000L // 30 min
        SessionTimerManager.startTimer(context, duration, forceRestart = true)
        val quotaBeforePause = sessionPrefs.getTimeRemainingMillis()

        // User pauses simulation
        SessionTimerManager.pauseTimer(context)
        assertTrue("isSessionPaused must be true", sessionPrefs.isSessionPaused)
        assertFalse("Timer state isRunning must be false when paused", SessionTimerManager.timerState.value.isRunning)

        // Wait while paused
        Thread.sleep(150)
        assertEquals("Remaining quota must freeze completely while paused", quotaBeforePause, sessionPrefs.getTimeRemainingMillis())

        // User resumes simulation
        SessionTimerManager.resumeExistingTimer(context)
        assertFalse("isSessionPaused must be false after resume", sessionPrefs.isSessionPaused)
        assertTrue("Timer state isRunning must be true after resume", SessionTimerManager.timerState.value.isRunning)
        assertTrue("Remaining quota after resume must match frozen quota", sessionPrefs.getTimeRemainingMillis() in (quotaBeforePause - 5000L)..quotaBeforePause)
    }
}
