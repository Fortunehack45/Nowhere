package com.fakegps.mocklocation.automation.engine

import org.json.JSONObject
import java.util.Calendar
import java.util.Random

object ScheduleRecurrenceCalculator {

    const val TYPE_ONE_TIME = "ONE_TIME"
    const val TYPE_HOURLY = "HOURLY"
    const val TYPE_DAILY = "DAILY"
    const val TYPE_WEEKLY = "WEEKLY"
    const val TYPE_MONTHLY = "MONTHLY"
    const val TYPE_CUSTOM_INTERVAL = "CUSTOM_INTERVAL"

    /**
     * Calculates the raw next trigger timestamp based on recurrence type and JSON configuration.
     */
    fun calculateNextRawTrigger(
        recurrenceType: String,
        configJson: String,
        fromTimestamp: Long = System.currentTimeMillis()
    ): Long? {
        val config = try {
            JSONObject(configJson)
        } catch (e: Exception) {
            JSONObject()
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = fromTimestamp
        }

        return when (recurrenceType) {
            TYPE_ONE_TIME -> {
                val targetTime = config.optLong("targetTimestamp", 0L)
                if (targetTime > fromTimestamp) targetTime else null
            }
            TYPE_HOURLY -> {
                val minute = config.optInt("minute", 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= fromTimestamp) {
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                }
                cal.timeInMillis
            }
            TYPE_DAILY -> {
                val hour = config.optInt("hour", 9)
                val minute = config.optInt("minute", 0)
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= fromTimestamp) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }
            TYPE_WEEKLY -> {
                val hour = config.optInt("hour", 9)
                val minute = config.optInt("minute", 0)
                val daysArray = config.optJSONArray("daysOfWeek")
                val activeDays = mutableSetOf<Int>()
                if (daysArray != null) {
                    for (i in 0 until daysArray.length()) {
                        activeDays.add(daysArray.getInt(i))
                    }
                }
                if (activeDays.isEmpty()) {
                    // Default to Monday - Friday if not specified
                    activeDays.addAll(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY))
                }

                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                var found = false
                for (step in 0..7) {
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                    if (activeDays.contains(dayOfWeek) && cal.timeInMillis > fromTimestamp) {
                        found = true
                        break
                    }
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (found) cal.timeInMillis else null
            }
            TYPE_MONTHLY -> {
                val dayOfMonth = config.optInt("dayOfMonth", 1).coerceIn(1, 31)
                val hour = config.optInt("hour", 9)
                val minute = config.optInt("minute", 0)

                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                if (cal.timeInMillis <= fromTimestamp) {
                    cal.add(Calendar.MONTH, 1)
                    cal.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
                cal.timeInMillis
            }
            TYPE_CUSTOM_INTERVAL -> {
                val intervalMinutes = config.optInt("intervalMinutes", 60).coerceAtLeast(1)
                fromTimestamp + (intervalMinutes * 60_000L)
            }
            else -> null
        }
    }

    /**
     * Applies uniform random jitter in [-jitterMinutes, +jitterMinutes] to the given timestamp.
     * Ensures the result is never earlier than minAllowedTime.
     */
    fun applyJitter(
        baseTimestamp: Long,
        jitterMinutes: Int,
        minAllowedTime: Long = System.currentTimeMillis(),
        random: Random = Random()
    ): Long {
        if (jitterMinutes <= 0) return baseTimestamp.coerceAtLeast(minAllowedTime)
        val jitterRangeMillis = jitterMinutes * 60_000L
        val randomOffset = (random.nextLong() % (jitterRangeMillis * 2 + 1)) - jitterRangeMillis
        val jittered = baseTimestamp + randomOffset
        return jittered.coerceAtLeast(minAllowedTime)
    }

    /**
     * Checks whether a given timestamp falls within quiet hours (e.g. 1:00 AM to 6:00 AM).
     * Handles midnight wrap-around (e.g. 23:00 to 06:00).
     */
    fun isDuringQuietHours(timestamp: Long, startMinute: Int, endMinute: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val currentMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (startMinute <= endMinute) {
            currentMinuteOfDay in startMinute until endMinute
        } else {
            // Wraps past midnight (e.g. 22:00 -> 06:00)
            currentMinuteOfDay >= startMinute || currentMinuteOfDay < endMinute
        }
    }

    /**
     * Finds the end of the quiet hours period relative to a timestamp.
     */
    fun getQuietHoursEndTimestamp(timestamp: Long, endMinute: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val endHour = endMinute / 60
        val endMin = endMinute % 60

        cal.set(Calendar.HOUR_OF_DAY, endHour)
        cal.set(Calendar.MINUTE, endMin)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (cal.timeInMillis <= timestamp) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Complete calculation pipeline: Raw recurrence -> Quiet hours check (Delay or Skip) -> Jitter.
     */
    fun calculateNextTriggerWithSafeguards(
        recurrenceType: String,
        configJson: String,
        fromTimestamp: Long = System.currentTimeMillis(),
        jitterMinutes: Int = 4,
        quietHoursEnabled: Boolean = false,
        quietHoursStartMinute: Int = 60,
        quietHoursEndMinute: Int = 360,
        quietHoursMode: String = "DELAY",
        random: Random = Random()
    ): Long? {
        var rawNext = calculateNextRawTrigger(recurrenceType, configJson, fromTimestamp) ?: return null

        if (quietHoursEnabled && isDuringQuietHours(rawNext, quietHoursStartMinute, quietHoursEndMinute)) {
            if (quietHoursMode.equals("DELAY", ignoreCase = true)) {
                rawNext = getQuietHoursEndTimestamp(rawNext, quietHoursEndMinute)
            } else {
                // SKIP mode: Calculate next trigger starting from the end of quiet hours
                val quietEnd = getQuietHoursEndTimestamp(rawNext, quietHoursEndMinute)
                rawNext = calculateNextRawTrigger(recurrenceType, configJson, quietEnd) ?: return null
            }
        }

        return applyJitter(rawNext, jitterMinutes, fromTimestamp + 1000L, random)
    }
}
