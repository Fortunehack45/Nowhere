package com.fakegps.mocklocation.automation.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.automation.data.AutomationLogEntity
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.util.ThemeColorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

object AutomationTargetResolver {

    const val TARGET_SINGLE_LOCATION = "SINGLE_LOCATION"
    const val TARGET_ROUTE = "ROUTE"

    private val isBatterySuppressed = AtomicBoolean(false)

    /**
     * Reads current battery percentage (0..100) from system sticky intent.
     */
    fun getBatteryPercentage(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100f).toInt()
        } else {
            100 // Fallback if cannot read
        }
    }

    fun isBatterySuppressedState(): Boolean = isBatterySuppressed.get()

    fun resetBatterySuppressionState() {
        isBatterySuppressed.set(false)
    }

    /**
     * Resolves target, verifies safeguards, dispatches to MockLocationService, logs event, and alerts user.
     * Returns true if successfully triggered, false if suppressed or failed.
     */
    suspend fun resolveAndDispatch(
        context: Context,
        targetType: String,
        targetId: Long,
        sourceDescription: String
    ): Boolean = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val settings = db.automationSettingsDao().getSettings()

        // 1. Check Battery Guardrail with Hysteresis
        if (settings != null && settings.batteryGuardEnabled) {
            val batteryPct = getBatteryPercentage(context)
            if (isBatterySuppressed.get()) {
                if (batteryPct < settings.batteryResumePercent) {
                    val reason = "Suppressed: Battery ($batteryPct%) has not recovered to resume threshold (${settings.batteryResumePercent}%)"
                    db.automationLogDao().logEvent(
                        AutomationLogEntity(
                            source = sourceDescription,
                            targetSummary = "Target #$targetId ($targetType)",
                            details = reason
                        )
                    )
                    AutomationNotificationManager.notifyTriggerSuppressed(context, reason)
                    return@withContext false
                } else {
                    // Battery recovered past hysteresis threshold
                    isBatterySuppressed.set(false)
                }
            } else {
                if (batteryPct < settings.batteryThresholdPercent) {
                    isBatterySuppressed.set(true)
                    val reason = "Suppressed: Battery ($batteryPct%) is below cutoff threshold (${settings.batteryThresholdPercent}%)"
                    db.automationLogDao().logEvent(
                        AutomationLogEntity(
                            source = sourceDescription,
                            targetSummary = "Target #$targetId ($targetType)",
                            details = reason
                        )
                    )
                    AutomationNotificationManager.notifyTriggerSuppressed(context, reason)
                    return@withContext false
                }
            }
        }

        // 2. Check Quiet Hours Guardrail
        if (settings != null && settings.quietHoursEnabled) {
            val now = System.currentTimeMillis()
            if (ScheduleRecurrenceCalculator.isDuringQuietHours(now, settings.quietHoursStartMinute, settings.quietHoursEndMinute)) {
                val reason = if (settings.quietHoursMode.equals("SKIP", ignoreCase = true)) {
                    "Skipped: Currently in Quiet Hours (${settings.quietHoursStartMinute / 60}:00 - ${settings.quietHoursEndMinute / 60}:00)"
                } else {
                    "Delayed: Quiet Hours active. Execution queued for conclusion of quiet window."
                }
                db.automationLogDao().logEvent(
                    AutomationLogEntity(
                        source = sourceDescription,
                        targetSummary = "Target #$targetId ($targetType)",
                        details = reason
                    )
                )
                return@withContext false
            }
        }

        // 3. Resolve Target and Dispatch to MockLocationService
        var targetSummary = "Target #$targetId"
        var dispatchSuccess = false

        if (targetType.equals(TARGET_SINGLE_LOCATION, ignoreCase = true)) {
            val favorite = db.favoriteDao().getFavoriteById(targetId)
            val sessionPrefs = SessionPreferences(context)
            val lat = favorite?.latitude ?: sessionPrefs.lastLatitude
            val lon = favorite?.longitude ?: sessionPrefs.lastLongitude
            targetSummary = favorite?.name ?: "Location ($lat, $lon)"

            val startIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_FIXED
                putExtra(MockLocationService.EXTRA_LATITUDE, lat)
                putExtra(MockLocationService.EXTRA_LONGITUDE, lon)
                putExtra(MockLocationService.EXTRA_ALTITUDE, 10.0)
            }
            try {
                ContextCompat.startForegroundService(context, startIntent)
                dispatchSuccess = true
            } catch (e: Exception) {
                dispatchSuccess = false
            }
        } else if (targetType.equals(TARGET_ROUTE, ignoreCase = true)) {
            val route = db.savedRouteDao().getRouteById(targetId)
            if (route != null) {
                targetSummary = "Route: ${route.name}"
                val waypoints = mutableListOf<RoutePoint>()
                try {
                    val array = JSONArray(route.waypointsJson)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        waypoints.add(
                            RoutePoint(
                                latitude = obj.getDouble("latitude"),
                                longitude = obj.getDouble("longitude"),
                                altitude = obj.optDouble("altitude", 10.0),
                                targetSpeedMps = (obj.optDouble("speedKmh", route.defaultSpeedKmh.toDouble()).toFloat() / 3.6f)
                            )
                        )
                    }
                } catch (e: Exception) {
                    // JSON parsing fallback
                }

                if (waypoints.size >= 2) {
                    val sessionPrefs = SessionPreferences(context)
                    sessionPrefs.saveWaypoints(waypoints)
                    sessionPrefs.lastSpeedKmh = route.defaultSpeedKmh
                    sessionPrefs.isLooping = route.isLooping

                    val startIntent = Intent(context, MockLocationService::class.java).apply {
                        action = MockLocationService.ACTION_START_ROUTE
                        putExtra(MockLocationService.EXTRA_SPEED_KMH, route.defaultSpeedKmh)
                        putExtra(MockLocationService.EXTRA_IS_LOOPING, route.isLooping)
                    }
                    try {
                        ContextCompat.startForegroundService(context, startIntent)
                        dispatchSuccess = true
                    } catch (e: Exception) {
                        dispatchSuccess = false
                    }
                }
            }
        }

        // 4. Log and Alert
        val logDetails = if (dispatchSuccess) {
            "Activated successfully via $sourceDescription"
        } else {
            "Dispatch failed to start target via $sourceDescription"
        }

        db.automationLogDao().logEvent(
            AutomationLogEntity(
                source = sourceDescription,
                targetSummary = targetSummary,
                details = logDetails
            )
        )

        if (dispatchSuccess) {
            AutomationNotificationManager.notifyTriggerExecuted(
                context,
                "Automation Active",
                "$targetSummary activated by $sourceDescription"
            )
            ThemeColorManager.updateAllAppWidgets(context)
        }

        dispatchSuccess
    }
}
