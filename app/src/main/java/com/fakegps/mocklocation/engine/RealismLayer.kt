package com.fakegps.mocklocation.engine

import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import java.util.Random
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Handles realism enhancements when explicitly enabled in settings.
 * Defaults to completely stable, static, shake-free coordinates for Fixed mode.
 */
class RealismLayer(
    private val settingsPrefs: AppSettingsPreferences? = null,
    private val random: Random = Random()
) {
    /**
     * Applies realistic 2D polar/Gaussian jitter when explicitly enabled.
     */
    fun applyJitter(latitude: Double, longitude: Double, forceJitter: Boolean = false): Pair<Double, Double> {
        val shouldJitter = forceJitter || (settingsPrefs?.randomizeJitter == true)
        if (!shouldJitter) {
            return truncateIfNeeded(latitude, longitude)
        }

        val configuredRadius = settingsPrefs?.jitterRadiusMeters ?: 2.0f
        val maxJitter = if (configuredRadius >= 0.1f) configuredRadius.toDouble() else 2.0
        val minJitter = (maxJitter * 0.25).coerceAtLeast(0.1)
        val radius = minJitter + (maxJitter - minJitter) * sqrt(random.nextDouble())
        val angleDeg = random.nextFloat() * 360f

        val (jitteredLat, jitteredLon) = GeoUtils.computeDestinationPoint(latitude, longitude, angleDeg, radius)
        return truncateIfNeeded(jitteredLat, jitteredLon)
    }

    /**
     * Truncates coordinates to configured decimal precision if enabled.
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
     * Generates horizontal accuracy in meters.
     */
    fun generateHorizontalAccuracy(isMoving: Boolean = false): Float {
        val base = settingsPrefs?.baseAccuracy ?: 1.0f
        if (!isMoving) return base.coerceAtLeast(0.5f)

        val shouldRandomize = settingsPrefs?.randomizeJitter ?: false
        return if (shouldRandomize) {
            val variance = (random.nextFloat() * 0.6f) - 0.3f
            (base + variance).coerceAtLeast(0.5f)
        } else {
            base.coerceAtLeast(0.5f)
        }
    }

    /**
     * Generates vertical altitude in meters.
     */
    fun generateAltitude(requestedAltitude: Double, isMoving: Boolean = false): Double {
        val defaultAlt = (settingsPrefs?.defaultAltitude?.toDouble() ?: requestedAltitude)
        val targetAlt = if (requestedAltitude > 0.1) requestedAltitude else defaultAlt
        val shouldRandomize = isMoving && (settingsPrefs?.randomizeAltitude ?: false)

        return if (shouldRandomize) {
            val variance = (random.nextDouble() * 0.8) - 0.4
            targetAlt + variance
        } else {
            targetAlt
        }
    }

    fun generateVerticalAccuracy(isMoving: Boolean = false): Float = if (isMoving) 1.5f else 0.5f

    fun generateSpeedAccuracy(isMoving: Boolean = false): Float = if (isMoving) 0.1f else 0.0f

    fun generateBearingAccuracy(isMoving: Boolean = false): Float = if (isMoving) 1.0f else 0.0f

    fun getAdaptiveIntervalMs(isMoving: Boolean): Long {
        return if (isMoving) {
            settingsPrefs?.updateIntervalMovingMs ?: 1000L
        } else {
            settingsPrefs?.updateIntervalStationaryMs ?: 5000L
        }
    }
}
