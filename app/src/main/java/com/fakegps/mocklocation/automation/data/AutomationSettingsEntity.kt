package com.fakegps.mocklocation.automation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_settings")
data class AutomationSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    // Master Toggles (all default to false)
    val scheduledAutomationEnabled: Boolean = false,
    val wifiTriggersEnabled: Boolean = false,
    val motionSyncEnabled: Boolean = false,
    val terrainLockEnabled: Boolean = true, // Default ON when motion sync is enabled

    // Sub-Feature 4: Terrain Lock Advanced Settings
    val terrainRestrictedEnabled: Boolean = false, // Opt-in extra check
    val terrainSearchRadiusMeters: Float = 25.0f, // Default 25m
    val terrainAllowUnmapped: Boolean = true, // Permissive (default ON) vs Strict (Hold position)

    // Sub-Feature 5: Natural Safeguards
    val jitterMinutes: Int = 4, // ±0 to 4 minutes jitter
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = 60, // 1:00 AM (minutes from midnight)
    val quietHoursEndMinute: Int = 360, // 6:00 AM (minutes from midnight)
    val quietHoursMode: String = "DELAY", // "DELAY" (queue until end) or "SKIP" (skip entirely)

    // Battery Guardrail
    val batteryGuardEnabled: Boolean = true,
    val batteryThresholdPercent: Int = 15, // Suppress triggers below 15%
    val batteryResumePercent: Int = 20 // Hysteresis: resume above 20%
)
