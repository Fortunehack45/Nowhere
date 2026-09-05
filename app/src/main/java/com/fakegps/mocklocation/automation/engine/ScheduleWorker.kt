package com.fakegps.mocklocation.automation.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fakegps.mocklocation.automation.data.ScheduleEntity
import com.fakegps.mocklocation.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_SCHEDULE_ID = "key_schedule_id"
        const val KEY_STEP_INDEX = "key_step_index"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1L)
        val stepIndex = inputData.getInt(KEY_STEP_INDEX, 0)

        if (scheduleId <= 0L) {
            return@withContext Result.failure()
        }

        val db = AppDatabase.getInstance(applicationContext)
        val settings = db.automationSettingsDao().getSettings()

        // Verify master schedule toggle
        if (settings == null || !settings.scheduledAutomationEnabled) {
            ScheduleExecutor.cancelAll(applicationContext)
            return@withContext Result.success()
        }

        val schedule = db.scheduleDao().getScheduleById(scheduleId)
        if (schedule == null || !schedule.enabled) {
            return@withContext Result.success()
        }

        val steps = db.scheduleDao().getStepsForSchedule(scheduleId)
        if (steps.isEmpty()) {
            return@withContext Result.success()
        }

        val currentStep = steps.getOrNull(stepIndex) ?: return@withContext Result.success()

        // Execute current step
        AutomationTargetResolver.resolveAndDispatch(
            applicationContext,
            currentStep.targetType,
            currentStep.targetId,
            "Schedule: ${schedule.name} [Step ${stepIndex + 1}/${steps.size}]"
        )

        // Check if more steps exist in this sequence
        if (stepIndex + 1 < steps.size) {
            val nextStep = steps[stepIndex + 1]
            val offsetDiffMinutes = (nextStep.triggerOffsetMinutes - currentStep.triggerOffsetMinutes).coerceAtLeast(1)
            ScheduleExecutor.enqueueStep(
                applicationContext,
                schedule.id,
                stepIndex + 1,
                offsetDiffMinutes * 60_000L
            )
        } else {
            // Last step finished. Record timestamp and compute next recurrence
            val now = System.currentTimeMillis()
            val nextTime = ScheduleRecurrenceCalculator.calculateNextTriggerWithSafeguards(
                recurrenceType = schedule.recurrenceType,
                configJson = schedule.recurrenceConfig,
                fromTimestamp = now,
                jitterMinutes = settings.jitterMinutes,
                quietHoursEnabled = settings.quietHoursEnabled,
                quietHoursStartMinute = settings.quietHoursStartMinute,
                quietHoursEndMinute = settings.quietHoursEndMinute,
                quietHoursMode = settings.quietHoursMode
            )

            if (nextTime != null && (schedule.loop || schedule.recurrenceType != ScheduleRecurrenceCalculator.TYPE_ONE_TIME)) {
                db.scheduleDao().updateTriggerTimestamps(schedule.id, now, nextTime)
                val delayMillis = (nextTime - now).coerceAtLeast(1000L)
                ScheduleExecutor.enqueueStep(
                    applicationContext,
                    schedule.id,
                    0,
                    delayMillis
                )
            } else {
                // One-time schedule completed
                db.scheduleDao().updateTriggerTimestamps(schedule.id, now, 0L)
                db.scheduleDao().setScheduleEnabled(schedule.id, false)
            }
        }

        Result.success()
    }
}
