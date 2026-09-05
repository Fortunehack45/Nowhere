package com.fakegps.mocklocation.automation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_logs")
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val source: String, // SCHEDULE, WIFI, MOTION, TERRAIN
    val targetSummary: String, // e.g. "Work HQ (Location)", "Morning Route (Route)"
    val details: String, // e.g. "Triggered via schedule 'Morning Commute'", "TERRAIN_BLOCKED at (37.77, -122.41)"
    val timestamp: Long = System.currentTimeMillis()
)
