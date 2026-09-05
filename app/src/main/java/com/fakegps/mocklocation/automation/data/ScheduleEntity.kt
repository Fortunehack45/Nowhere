package com.fakegps.mocklocation.automation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = false,
    val recurrenceType: String, // ONE_TIME, HOURLY, DAILY, WEEKLY, MONTHLY, CUSTOM_INTERVAL
    val recurrenceConfig: String = "{}", // JSON: {"daysOfWeek":[1,3,5],"dayOfMonth":15,"intervalMinutes":60,"hour":9,"minute":0}
    val loop: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val nextTriggerAt: Long = 0L
)
