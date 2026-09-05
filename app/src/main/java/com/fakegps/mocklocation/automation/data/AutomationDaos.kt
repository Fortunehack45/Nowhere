package com.fakegps.mocklocation.automation.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ScheduleWithSteps(
    @Embedded val schedule: ScheduleEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "scheduleId"
    )
    val steps: List<ScheduleStepEntity>
)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM automation_schedules ORDER BY createdAt DESC")
    fun getAllSchedules(): Flow<List<ScheduleEntity>>

    @Transaction
    @Query("SELECT * FROM automation_schedules WHERE enabled = 1")
    fun getEnabledSchedulesWithSteps(): Flow<List<ScheduleWithSteps>>

    @Transaction
    @Query("SELECT * FROM automation_schedules WHERE id = :scheduleId")
    suspend fun getScheduleWithSteps(scheduleId: Long): ScheduleWithSteps?

    @Query("SELECT * FROM automation_schedules WHERE id = :scheduleId")
    suspend fun getScheduleById(scheduleId: Long): ScheduleEntity?

    @Query("SELECT * FROM automation_schedule_steps WHERE scheduleId = :scheduleId ORDER BY orderIndex ASC")
    suspend fun getStepsForSchedule(scheduleId: Long): List<ScheduleStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<ScheduleStepEntity>)

    @Query("DELETE FROM automation_schedule_steps WHERE scheduleId = :scheduleId")
    suspend fun deleteStepsForSchedule(scheduleId: Long)

    @Transaction
    suspend fun insertOrUpdateScheduleWithSteps(schedule: ScheduleEntity, steps: List<ScheduleStepEntity>): Long {
        val scheduleId = if (schedule.id == 0L) {
            insertSchedule(schedule)
        } else {
            updateSchedule(schedule)
            schedule.id
        }
        deleteStepsForSchedule(scheduleId)
        val stepsWithId = steps.mapIndexed { index, step ->
            step.copy(scheduleId = scheduleId, orderIndex = index)
        }
        insertSteps(stepsWithId)
        return scheduleId
    }

    @Query("UPDATE automation_schedules SET enabled = :enabled WHERE id = :scheduleId")
    suspend fun setScheduleEnabled(scheduleId: Long, enabled: Boolean)

    @Query("UPDATE automation_schedules SET lastTriggeredAt = :timestamp, nextTriggerAt = :nextTrigger WHERE id = :scheduleId")
    suspend fun updateTriggerTimestamps(scheduleId: Long, timestamp: Long, nextTrigger: Long)
}

@Dao
interface WifiTriggerDao {
    @Query("SELECT * FROM automation_wifi_triggers ORDER BY createdAt DESC")
    fun getAllWifiTriggers(): Flow<List<WifiTriggerEntity>>

    @Query("SELECT * FROM automation_wifi_triggers WHERE enabled = 1 AND ssid = :ssid AND triggerType = :triggerType")
    suspend fun getEnabledTriggersForSsid(ssid: String, triggerType: String): List<WifiTriggerEntity>

    @Query("SELECT * FROM automation_wifi_triggers WHERE id = :id")
    suspend fun getWifiTriggerById(id: Long): WifiTriggerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWifiTrigger(trigger: WifiTriggerEntity): Long

    @Update
    suspend fun updateWifiTrigger(trigger: WifiTriggerEntity)

    @Delete
    suspend fun deleteWifiTrigger(trigger: WifiTriggerEntity)

    @Query("UPDATE automation_wifi_triggers SET enabled = :enabled WHERE id = :id")
    suspend fun setWifiTriggerEnabled(id: Long, enabled: Boolean)
}

@Dao
interface AutomationSettingsDao {
    @Query("SELECT * FROM automation_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AutomationSettingsEntity?>

    @Query("SELECT * FROM automation_settings WHERE id = 1")
    suspend fun getSettings(): AutomationSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSettings(settings: AutomationSettingsEntity)

    @Query("UPDATE automation_settings SET scheduledAutomationEnabled = :enabled WHERE id = 1")
    suspend fun setScheduledAutomationEnabled(enabled: Boolean)

    @Query("UPDATE automation_settings SET wifiTriggersEnabled = :enabled WHERE id = 1")
    suspend fun setWifiTriggersEnabled(enabled: Boolean)

    @Query("UPDATE automation_settings SET motionSyncEnabled = :enabled WHERE id = 1")
    suspend fun setMotionSyncEnabled(enabled: Boolean)

    @Query("UPDATE automation_settings SET terrainLockEnabled = :enabled WHERE id = 1")
    suspend fun setTerrainLockEnabled(enabled: Boolean)

    @Query("UPDATE automation_settings SET terrainSearchRadiusMeters = :radius WHERE id = 1")
    suspend fun setTerrainSearchRadius(radius: Float)

    @Query("UPDATE automation_settings SET terrainRestrictedEnabled = :enabled WHERE id = 1")
    suspend fun setTerrainRestrictedEnabled(enabled: Boolean)

    @Query("UPDATE automation_settings SET terrainAllowUnmapped = :allowed WHERE id = 1")
    suspend fun setTerrainAllowUnmapped(allowed: Boolean)
}

@Dao
interface AutomationLogDao {
    @Query("SELECT * FROM automation_logs ORDER BY timestamp DESC LIMIT 20")
    fun getLast20Logs(): Flow<List<AutomationLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AutomationLogEntity)

    @Query("DELETE FROM automation_logs WHERE id NOT IN (SELECT id FROM automation_logs ORDER BY timestamp DESC LIMIT 20)")
    suspend fun trimOldLogs()

    @Transaction
    suspend fun logEvent(log: AutomationLogEntity) {
        insertLog(log)
        trimOldLogs()
    }
}
