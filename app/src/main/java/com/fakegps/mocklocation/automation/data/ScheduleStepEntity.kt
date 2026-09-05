package com.fakegps.mocklocation.automation.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "automation_schedule_steps",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("scheduleId")]
)
data class ScheduleStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scheduleId: Long,
    val orderIndex: Int,
    val targetType: String, // SINGLE_LOCATION, ROUTE
    val targetId: Long,
    val triggerOffsetMinutes: Int = 0 // Offset in minutes relative to schedule base trigger time
)
