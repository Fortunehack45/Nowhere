package com.fakegps.mocklocation.automation.engine

import android.content.Context
import androidx.work.*
import com.fakegps.mocklocation.automation.data.ScheduleEntity
import com.fakegps.mocklocation.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object ScheduleExecutor {

    private const val WORK_TAG_PREFIX = "automation_schedule_"
    private const val GLOBAL_WORK_TAG = "automation_schedule_work"

    fun getWorkTag(scheduleId: Long): String = "$WORK_TAG_PREFIX$scheduleId"

    fun enqueueStep(
        context: Context,
        scheduleId: Long,
        stepIndex: Int,
        delayMillis: Long
    ) {
        val workData = workDataOf(
            ScheduleWorker.KEY_SCHEDULE_ID to scheduleId,
            ScheduleWorker.KEY_STEP_INDEX to stepIndex
        )

        val workRequest = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(workData)
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(GLOBAL_WORK_TAG)
            .addTag(getWorkTag(scheduleId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${getWorkTag(scheduleId)}_step_$stepIndex",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    suspend fun scheduleOne(context: Context, scheduleId: Long) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val settings = db.automationSettingsDao().getSettings()

        if (settings == null || !settings.scheduledAutomationEnabled) {
            cancelOne(context, scheduleId)
            return@withContext
        }

        val schedule = db.scheduleDao().getScheduleById(scheduleId)
        if (schedule == null || !schedule.enabled) {
            cancelOne(context, scheduleId)
            return@withContext
        }

        val now = System.currentTimeMillis()
        var nextTime = schedule.nextTriggerAt
        if (nextTime <= now) {
            nextTime = ScheduleRecurrenceCalculator.calculateNextTriggerWithSafeguards(
                recurrenceType = schedule.recurrenceType,
                configJson = schedule.recurrenceConfig,
                fromTimestamp = now,
                jitterMinutes = settings.jitterMinutes,
                quietHoursEnabled = settings.quietHoursEnabled,
                quietHoursStartMinute = settings.quietHoursStartMinute,
                quietHoursEndMinute = settings.quietHoursEndMinute,
                quietHoursMode = settings.quietHoursMode
            ) ?: 0L
            if (nextTime > 0L) {
                db.scheduleDao().updateTriggerTimestamps(schedule.id, schedule.lastTriggeredAt ?: 0L, nextTime)
            }
        }

        if (nextTime > now) {
            val delayMillis = nextTime - now
            enqueueStep(context, scheduleId, 0, delayMillis)
        } else {
            cancelOne(context, scheduleId)
        }
    }

    fun cancelOne(context: Context, scheduleId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(getWorkTag(scheduleId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(GLOBAL_WORK_TAG)
    }

    suspend fun scheduleAllEnabled(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val settings = db.automationSettingsDao().getSettings()

        if (settings == null || !settings.scheduledAutomationEnabled) {
            cancelAll(context)
            return@withContext
        }

        val schedules = db.scheduleDao().getScheduleById(0L) // Or get all enabled
        // Query enabled schedules directly
        // We will schedule each enabled schedule
        val allSchedules = db.openHelper.readableDatabase.query("SELECT id FROM automation_schedules WHERE enabled = 1")
        val ids = mutableListOf<Long>()
        while (allSchedules.moveToNext()) {
            ids.add(allSchedules.getLong(0))
        }
        allSchedules.close()

        for (id in ids) {
            scheduleOne(context, id)
        }
    }
}
