package com.fakegps.mocklocation.automation

import com.fakegps.mocklocation.automation.engine.ScheduleRecurrenceCalculator
import com.fakegps.mocklocation.automation.engine.TerrainLockEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Random

class AutomationEngineTest {

    @Before
    fun setUp() {
        TerrainLockEngine.clearCache()
        TerrainLockEngine.mockClassifier = null
        TerrainLockEngine.mockNearestWalkableFinder = null
    }

    // =========================================================================
    // 1. Recurrence Calculations (ScheduleRecurrenceCalculator)
    // =========================================================================

    @Test
    fun testOneTimeRecurrence_futureTimestamp_returnsExactTarget() {
        val now = 1700000000000L
        val target = now + 3600000L // 1 hour ahead
        val config = JSONObject().put("targetTimestamp", target).toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_ONE_TIME,
            config,
            fromTimestamp = now
        )

        assertEquals(target, next)
    }

    @Test
    fun testOneTimeRecurrence_pastTimestamp_returnsNull() {
        val now = 1700000000000L
        val target = now - 1000L // In the past
        val config = JSONObject().put("targetTimestamp", target).toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_ONE_TIME,
            config,
            fromTimestamp = now
        )

        assertNull(next)
    }

    @Test
    fun testHourlyRecurrence_calculatesNextHourBoundary() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 1, 10, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromTime = cal.timeInMillis

        // Recur hourly at minute 30 -> should trigger today at 10:30
        val config = JSONObject().put("minute", 30).toString()
        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_HOURLY,
            config,
            fromTimestamp = fromTime
        )

        assertNotNull(next)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(10, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, nextCal.get(Calendar.MINUTE))
        assertEquals(0, nextCal.get(Calendar.SECOND))

        // Recur hourly at minute 10 -> already passed 10:15, so should advance to 11:10
        val configPassed = JSONObject().put("minute", 10).toString()
        val nextHour = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_HOURLY,
            configPassed,
            fromTimestamp = fromTime
        )

        assertNotNull(nextHour)
        val nextHourCal = Calendar.getInstance().apply { timeInMillis = nextHour!! }
        assertEquals(11, nextHourCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, nextHourCal.get(Calendar.MINUTE))
    }

    @Test
    fun testDailyRecurrence_advancesToTomorrowIfTimePassed() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 1, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromTime = cal.timeInMillis

        // Recur daily at 9:00 AM -> already passed 14:00, must trigger tomorrow (May 2) at 09:00
        val config = JSONObject().apply {
            put("hour", 9)
            put("minute", 0)
        }.toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_DAILY,
            config,
            fromTimestamp = fromTime
        )

        assertNotNull(next)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(2, nextCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(Calendar.MINUTE))
    }

    @Test
    fun testWeeklyRecurrence_findsNextMatchingDay() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 4, 10, 0, 0) // May 4, 2026 is Monday
            set(Calendar.MILLISECOND, 0)
        }
        val fromTime = cal.timeInMillis

        // Active days: Wednesday (4) and Friday (6) at 15:00
        val config = JSONObject().apply {
            put("hour", 15)
            put("minute", 0)
            put("daysOfWeek", JSONArray().put(Calendar.WEDNESDAY).put(Calendar.FRIDAY))
        }.toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_WEEKLY,
            config,
            fromTimestamp = fromTime
        )

        assertNotNull(next)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.WEDNESDAY, nextCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(15, nextCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testMonthlyRecurrence_advancesToNextMonthIfPassed() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 20, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromTime = cal.timeInMillis

        // Day of month 15 at 12:00 -> passed May 15, should advance to June 15
        val config = JSONObject().apply {
            put("dayOfMonth", 15)
            put("hour", 12)
            put("minute", 0)
        }.toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_MONTHLY,
            config,
            fromTimestamp = fromTime
        )

        assertNotNull(next)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.JUNE, nextCal.get(Calendar.MONTH))
        assertEquals(15, nextCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, nextCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testCustomIntervalRecurrence_addsExactMinutes() {
        val fromTime = 1700000000000L
        val config = JSONObject().put("intervalMinutes", 45).toString()

        val next = ScheduleRecurrenceCalculator.calculateNextRawTrigger(
            ScheduleRecurrenceCalculator.TYPE_CUSTOM_INTERVAL,
            config,
            fromTimestamp = fromTime
        )

        assertEquals(fromTime + 45 * 60_000L, next)
    }

    // =========================================================================
    // 2. Natural Behavior Safeguards (Jitter & Quiet Hours)
    // =========================================================================

    @Test
    fun testJitterBounds_strictlyWithinRange() {
        val baseTime = 1700000000000L
        val jitterMinutes = 4
        val maxOffsetMs = jitterMinutes * 60_000L
        val random = Random(42)

        for (i in 0 until 100) {
            val jittered = ScheduleRecurrenceCalculator.applyJitter(
                baseTime,
                jitterMinutes,
                minAllowedTime = baseTime - maxOffsetMs,
                random = random
            )
            val delta = Math.abs(jittered - baseTime)
            assertTrue("Jitter $delta exceeds max allowed $maxOffsetMs", delta <= maxOffsetMs)
        }
    }

    @Test
    fun testQuietHours_detectionStandardAndMidnightWrap() {
        // Standard window: 1:00 AM (60) to 6:00 AM (360)
        val cal = Calendar.getInstance()

        cal.set(Calendar.HOUR_OF_DAY, 3)
        cal.set(Calendar.MINUTE, 30)
        assertTrue(ScheduleRecurrenceCalculator.isDuringQuietHours(cal.timeInMillis, 60, 360))

        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)
        assertFalse(ScheduleRecurrenceCalculator.isDuringQuietHours(cal.timeInMillis, 60, 360))

        // Midnight wrapping window: 22:00 (1320) to 06:00 (360)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        assertTrue(ScheduleRecurrenceCalculator.isDuringQuietHours(cal.timeInMillis, 1320, 360))

        cal.set(Calendar.HOUR_OF_DAY, 4)
        assertTrue(ScheduleRecurrenceCalculator.isDuringQuietHours(cal.timeInMillis, 1320, 360))

        cal.set(Calendar.HOUR_OF_DAY, 12)
        assertFalse(ScheduleRecurrenceCalculator.isDuringQuietHours(cal.timeInMillis, 1320, 360))
    }

    @Test
    fun testQuietHours_delayModeDefersToEnd() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromTime = cal.timeInMillis

        // Quiet hours: 1:00 AM (60) to 6:00 AM (360), Mode = DELAY, jitter = 0
        val config = JSONObject().apply {
            put("hour", 3)
            put("minute", 0)
        }.toString()

        val next = ScheduleRecurrenceCalculator.calculateNextTriggerWithSafeguards(
            recurrenceType = ScheduleRecurrenceCalculator.TYPE_DAILY,
            configJson = config,
            fromTimestamp = fromTime - 1000L,
            jitterMinutes = 0,
            quietHoursEnabled = true,
            quietHoursStartMinute = 60,
            quietHoursEndMinute = 360,
            quietHoursMode = "DELAY"
        )

        assertNotNull(next)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(6, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(Calendar.MINUTE))
    }

    // =========================================================================
    // 3. Terrain Lock Engine (Deflection, Gradual Steering, Hold)
    // =========================================================================

    @Test
    fun testTerrainLock_normalWalkableAccepted() = runBlocking {
        TerrainLockEngine.mockClassifier = { _, _ -> TerrainLockEngine.TerrainType.WALKABLE }

        val result = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = 0.0f,
            stepDistanceMeters = 5.0
        )

        assertTrue(result is TerrainLockEngine.TerrainStepResult.Accepted)
        val accepted = result as TerrainLockEngine.TerrainStepResult.Accepted
        assertTrue(accepted.lat > 37.7749)
        assertEquals(0.0f, accepted.bearing, 0.01f)
    }

    @Test
    fun testTerrainLock_deflectionArcChoosesWalkableDeflection() = runBlocking {
        // First projection (straight ahead) is WATER, candidate deflection is WALKABLE
        var callCount = 0
        TerrainLockEngine.mockClassifier = { _, _ ->
            callCount++
            if (callCount == 1) {
                TerrainLockEngine.TerrainType.WATER
            } else {
                TerrainLockEngine.TerrainType.WALKABLE
            }
        }

        val result = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = 0.0f,
            stepDistanceMeters = 10.0
        )

        assertTrue(result is TerrainLockEngine.TerrainStepResult.Deflected)
        val deflected = result as TerrainLockEngine.TerrainStepResult.Deflected
        assertTrue(deflected.deflectionAngleDeg in listOf(15f, -15f, 30f, -30f, 45f, -45f))
    }

    @Test
    fun testTerrainLock_gradualSteeringBlendingFormula() = runBlocking {
        // All forward projections and deflection angles blocked by water
        TerrainLockEngine.mockClassifier = { _, _ -> TerrainLockEngine.TerrainType.WATER }

        // Nearest walkable way is due East (90 degrees)
        val nearestLat = 37.7749
        val nearestLon = -122.4180
        TerrainLockEngine.mockNearestWalkableFinder = { _, _, _ -> Pair(nearestLat, nearestLon) }

        val currentHeading = 0.0f // North
        val steeringFactor = 0.20f

        val result = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = currentHeading,
            stepDistanceMeters = 5.0,
            steeringFactor = steeringFactor
        )

        assertTrue(result is TerrainLockEngine.TerrainStepResult.Steered)
        val steered = result as TerrainLockEngine.TerrainStepResult.Steered

        // Target heading is East (90°). Blended heading should be 0 + 0.20 * (90 - 0) = 18°
        assertEquals(18.0f, steered.bearing, 1.0f)
    }

    @Test
    fun testTerrainLock_holdPositionWhenCompletelyBlocked() = runBlocking {
        // Everything is water, and no nearest walkable coordinates found within radius
        TerrainLockEngine.mockClassifier = { _, _ -> TerrainLockEngine.TerrainType.WATER }
        TerrainLockEngine.mockNearestWalkableFinder = { _, _, _ -> null }

        val result = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = 0.0f,
            stepDistanceMeters = 5.0
        )

        assertTrue(result is TerrainLockEngine.TerrainStepResult.HoldPosition)
    }

    @Test
    fun testTerrainLock_unmappedPermissiveVsStrict() = runBlocking {
        TerrainLockEngine.mockClassifier = { _, _ -> TerrainLockEngine.TerrainType.UNKNOWN }

        // Permissive mode accepts UNKNOWN
        val permissiveResult = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = 0.0f,
            stepDistanceMeters = 5.0,
            allowUnmapped = true
        )
        assertTrue(permissiveResult is TerrainLockEngine.TerrainStepResult.Accepted)

        // Strict mode rejects UNKNOWN and holds position
        val strictResult = TerrainLockEngine.evaluateStep(
            context = null,
            currentLat = 37.7749,
            currentLon = -122.4194,
            currentHeading = 0.0f,
            stepDistanceMeters = 5.0,
            allowUnmapped = false
        )
        assertTrue(strictResult is TerrainLockEngine.TerrainStepResult.HoldPosition)
    }
}
