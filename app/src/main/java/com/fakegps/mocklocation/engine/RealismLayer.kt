package com.fakegps.mocklocation.engine

import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import java.util.Random
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Provides realism enhancements configured via AppSettingsPreferences:
 * GPS jitter, dynamic accuracy variance, coordinate precision truncation,
 * and adaptive battery-saving update intervals.
 */
class RealismLayer(
    private val settingsPrefs: AppSettingsPreferences? = null,
    private val random: Random = Random()
) {
    /**
     * Applies realistic 2D polar/Gaussian jitter to stationary coordinates.
     */
    fun applyJitter(latitude: Double, longitude: Double): Pair<Double, Double> {
        val shouldJitter = settingsPrefs?.randomizeJitter ?: true
        if (!shouldJitter) {
            return truncateIfNeeded(latitude, longitude)
        }

        val maxJitter = (settingsPrefs?.jitterRadiusMeters?.toDouble() ?: 2.0).coerceAtLeast(0.1)
        val minJitter = (maxJitter * 0.2).coerceAtLeast(0.05)
        val radius = minJitter + (maxJitter - minJitter) * sqrt(random.nextDouble())
        val angleDeg = random.nextFloat() * 360f

        val (jitteredLat, jitteredLon) = GeoUtils.computeDestinationPoint(latitude, longitude, angleDeg, radius)
        return truncateIfNeeded(jitteredLat, jitteredLon)
    }

    /**
     * Truncates coordinates to the configured decimal places (e.g. 6, 4, 2 decimals) if configured.
     */
    fun truncateIfNeeded(latitude: Double, longitude: Double): Pair<Double, Double> {
        val decimals = settingsPrefs?.truncateDecimals ?: -1
        if (decimals <= 0) return Pair(latitude, longitude)

        val factor = 10.0.pow(decimals.toDouble())
        val truncatedLat = round(latitude * factor) / factor
        val truncatedLon = round(longitude * factor) / factor
        return Pair(truncatedLat, truncatedLon)
    }

    /**
     * Generates horizontal accuracy in meters configured from base accuracy.
     */
    fun generateHorizontalAccuracy(): Float {
        val base = settingsPrefs?.baseAccuracy ?: 2.5f
        val variance = (random.nextFloat() * 1.5f) - 0.75f
        return (base + variance).coerceAtLeast(0.5f)
    }

    /**
     * Generates vertical altitude in meters with optional vertical variance.
     */
    fun generateAltitude(requestedAltitude: Double): Double {
        val defaultAlt = (settingsPrefs?.defaultAltitude?.toDouble() ?: requestedAltitude)
        val targetAlt = if (requestedAltitude > 0.1) requestedAltitude else defaultAlt
        val shouldRandomize = settingsPrefs?.randomizeAltitude ?: true

        return if (shouldRandomize) {
            val variance = (random.nextDouble() * 2.0) - 1.0
            targetAlt + variance
        } else {
            targetAlt
        }
    }

    fun generateVerticalAccuracy(): Float {
        return 2.5f + (random.nextFloat() * 2.5f)
    }

    fun generateSpeedAccuracy(): Float {
        return 0.1f + (random.nextFloat() * 0.3f)
    }

    fun generateBearingAccuracy(): Float {
        return 2.0f + (random.nextFloat() * 5.0f)
    }

    /**
     * Returns the update interval in milliseconds configured in settings.
     */
    fun getAdaptiveIntervalMs(isMoving: Boolean): Long {
        return if (isMoving) {
            settingsPrefs?.updateIntervalMovingMs ?: 1000L
        } else {
            settingsPrefs?.updateIntervalStationaryMs ?: 5000L
        }
    }
}
