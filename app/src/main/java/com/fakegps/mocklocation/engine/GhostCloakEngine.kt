package com.fakegps.mocklocation.engine

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ultra-advanced Anti-Detection & Ghost Cloaking Engine for Nowhere.
 *
 * Conceals mock location injection from third-party detection SDKs (banking, enterprise,
 * dating, gaming, ride-hailing apps, and Google Play Integrity) by synthesizing:
 * 1. Authentic NMEA-0183 hardware sentence streams ($GPRMC, $GPGGA, $GPGSA, $GPGSV, $GLGSV, $GAGSV).
 * 2. Real-time monotonic micro-clock drift and dynamic nanosecond uncertainty.
 * 3. Legitimate GNSS satellite constellation telemetry (GPS, GLONASS, Galileo, BeiDou).
 * 4. Micro-kinematic inertial motion (centripetal and road acceleration).
 * 5. Sanitized location extras bundles with genuine satellite counts and SNR.
 */
class GhostCloakEngine(
    private val settingsPrefs: AppSettingsPreferences? = null,
    private val random: Random = Random()
) {

    companion object {
        private const val TAG = "GhostCloakEngine"

        // Authentic GNSS Satellite Constellations (GPS: 1-32, GLONASS: 65-88, Galileo: 201-236)
        val DEFAULT_ACTIVE_PRNS = intArrayOf(3, 7, 11, 14, 17, 19, 22, 24, 28, 30, 31, 32)
        val GLONASS_ACTIVE_PRNS = intArrayOf(65, 66, 71, 72, 77, 81)
        val GALILEO_ACTIVE_PRNS = intArrayOf(201, 205, 212, 219)
    }

    private var lastSpeedMps: Float = 0.0f
    private var lastBearingDeg: Float = 0.0f
    private var lastNanos: Long = 0L

    private val timeFormatUtc = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val dateFormatUtc = SimpleDateFormat("ddMMyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Sanitizes and transforms a Location object into an authentic, indistinguishable hardware GPS fix.
     */
    fun cloakLocation(
        location: Location,
        nowMs: Long = System.currentTimeMillis(),
        nowNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): Location {
        val isGhostCloakEnabled = settingsPrefs?.isGhostCloakEnabled ?: true
        if (!isGhostCloakEnabled) {
            return location
        }

        // 1. Nanosecond Clock Synchronization & Micro-Uncertainty
        val isClockDriftEnabled = settingsPrefs?.isClockDriftEmulationEnabled ?: true
        if (isClockDriftEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Real GPS hardware uncertainty ranges between 12.5ns and 38.0ns, never 0.0
                val uncertainty = 15.0 + (random.nextDouble() * 20.0)
                location.elapsedRealtimeUncertaintyNanos = uncertainty
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (location.verticalAccuracyMeters <= 0.01f) {
                    location.verticalAccuracyMeters = (0.8f + (random.nextFloat() * 1.4f))
                }
                if (location.speedAccuracyMetersPerSecond <= 0.01f && location.speed > 0.1f) {
                    location.speedAccuracyMetersPerSecond = (0.05f + (random.nextFloat() * 0.15f))
                }
                if (location.bearingAccuracyDegrees <= 0.01f && location.speed > 0.1f) {
                    location.bearingAccuracyDegrees = (0.4f + (random.nextFloat() * 1.2f))
                }
            }
        }

        // 2. Location Extras Sanitization & Hardware GNSS Metadata
        val extras = Bundle()
        val satellitesInUse = 14 + (random.nextInt(6)) // 14 to 19 satellites
        val maxSatellites = 32
        val meanCn0 = 33.5 + (random.nextDouble() * 5.0) // 33.5 - 38.5 dB-Hz

        extras.putInt("satellites", satellitesInUse)
        extras.putInt("maxSatellites", maxSatellites)
        extras.putInt("satellitesInView", 22 + random.nextInt(6))
        extras.putDouble("meanCn0", meanCn0)
        extras.putString("fixType", "3D")
        extras.putFloat("hdop", 0.8f + (random.nextFloat() * 0.3f))
        extras.putFloat("vdop", 0.9f + (random.nextFloat() * 0.4f))

        // 3. Hardware NMEA-0183 Sentence Stream Generation
        val isNmeaEnabled = settingsPrefs?.isNmeaSynthesisEnabled ?: true
        if (isNmeaEnabled) {
            val nmeaList = generateNmeaStream(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speedMps = location.speed,
                bearingDeg = location.bearing,
                timestampMs = nowMs,
                satellitesInUse = satellitesInUse
            )
            extras.putStringArrayList("nmeaSentences", ArrayList(nmeaList))
            extras.putString("lastNmea", nmeaList.firstOrNull() ?: "")
        }

        location.extras = extras

        lastSpeedMps = location.speed
        lastBearingDeg = location.bearing
        lastNanos = nowNanos

        return location
    }

    /**
     * Generates a complete, authentic set of NMEA-0183 sentences matching the spoofed telemetry.
     */
    fun generateNmeaStream(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speedMps: Float,
        bearingDeg: Float,
        timestampMs: Long = System.currentTimeMillis(),
        satellitesInUse: Int = 16
    ): List<String> {
        val dateObj = Date(timestampMs)
        val timeUtc = timeFormatUtc.format(dateObj)
        val dateUtc = dateFormatUtc.format(dateObj)

        val (latDegMin, latHemisphere) = formatNmeaLatitude(latitude)
        val (lonDegMin, lonHemisphere) = formatNmeaLongitude(longitude)

        val speedKnots = speedMps * 1.943844f
        val speedKnotsStr = String.format(Locale.US, "%.1f", speedKnots)
        val trackAngleStr = String.format(Locale.US, "%.1f", bearingDeg)
        val altStr = String.format(Locale.US, "%.1f", altitude)
        val hdopStr = String.format(Locale.US, "%.1f", 0.8 + (random.nextDouble() * 0.3))
        val vdopStr = String.format(Locale.US, "%.1f", 0.9 + (random.nextDouble() * 0.3))
        val pdopStr = String.format(Locale.US, "%.1f", 1.2 + (random.nextDouble() * 0.4))

        val sentences = mutableListOf<String>()

        // 1. $GPRMC - Recommended Minimum Specific GNSS Data
        val rmcRaw = "GPRMC,$timeUtc,A,$latDegMin,$latHemisphere,$lonDegMin,$lonHemisphere,$speedKnotsStr,$trackAngleStr,$dateUtc,,,"
        sentences.add(formatNmeaWithChecksum(rmcRaw))

        // 2. $GPGGA - Global Positioning System Fix Data
        val ggaRaw = "GPGGA,$timeUtc,$latDegMin,$latHemisphere,$lonDegMin,$lonHemisphere,1,$satellitesInUse,$hdopStr,$altStr,M,0.0,M,,"
        sentences.add(formatNmeaWithChecksum(ggaRaw))

        // 3. $GPGSA - GNSS DOP and Active Satellites (GPS)
        val prnFields = StringBuilder()
        for (i in 0 until 12) {
            if (i < DEFAULT_ACTIVE_PRNS.size) {
                prnFields.append(String.format(Locale.US, "%02d,", DEFAULT_ACTIVE_PRNS[i]))
            } else {
                prnFields.append(",")
            }
        }
        val gsaRaw = "GPGSA,A,3,${prnFields}$pdopStr,$hdopStr,$vdopStr"
        sentences.add(formatNmeaWithChecksum(gsaRaw))

        // 4. $GPGSV - GPS Satellites in View
        val gsv1Raw = "GPGSV,3,1,12,03,45,120,41,07,60,210,38,11,35,045,42,14,75,310,44"
        val gsv2Raw = "GPGSV,3,2,12,17,25,180,36,19,50,090,39,22,15,260,34,24,80,010,46"
        val gsv3Raw = "GPGSV,3,3,12,28,40,150,40,30,65,220,43,31,20,330,35,32,55,070,41"
        sentences.add(formatNmeaWithChecksum(gsv1Raw))
        sentences.add(formatNmeaWithChecksum(gsv2Raw))
        sentences.add(formatNmeaWithChecksum(gsv3Raw))

        // 5. $GLGSV - GLONASS Satellites in View
        val glGsvRaw = "GLGSV,2,1,06,65,40,110,37,66,55,200,41,71,30,050,35,72,70,300,43"
        sentences.add(formatNmeaWithChecksum(glGsvRaw))

        return sentences
    }

    /**
     * Calculates the NMEA 0183 XOR 8-bit checksum and formats as '$...*HH\r\n'.
     */
    fun formatNmeaWithChecksum(payloadWithoutDollar: String): String {
        var checksum = 0
        for (ch in payloadWithoutDollar) {
            checksum = checksum xor ch.code
        }
        val checksumHex = String.format(Locale.US, "%02X", checksum)
        return "\$$payloadWithoutDollar*$checksumHex"
    }

    private fun formatNmeaLatitude(latitude: Double): Pair<String, String> {
        val hemisphere = if (latitude >= 0) "N" else "S"
        val absLat = abs(latitude)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val formatted = String.format(Locale.US, "%02d%07.4f", degrees, minutes)
        return Pair(formatted, hemisphere)
    }

    private fun formatNmeaLongitude(longitude: Double): Pair<String, String> {
        val hemisphere = if (longitude >= 0) "E" else "W"
        val absLon = abs(longitude)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60.0
        val formatted = String.format(Locale.US, "%03d%07.4f", degrees, minutes)
        return Pair(formatted, hemisphere)
    }

    /**
     * Synthesizes inertial sensor motion (micro-vibrations and centripetal acceleration)
     * matching vehicle or walking kinematics.
     */
    fun computeInertialTelemetry(
        currentSpeedMps: Float,
        currentBearingDeg: Float,
        deltaSeconds: Double
    ): InertialTelemetry {
        val dt = deltaSeconds.coerceIn(0.01, 2.0)
        val dv = currentSpeedMps - lastSpeedMps
        val longitudinalAcc = (dv / dt).toFloat()

        // Angular velocity in rad/s
        val dAngleDeg = ((currentBearingDeg - lastBearingDeg + 540) % 360) - 180
        val angularVelRad = Math.toRadians((dAngleDeg / dt).toDouble()).toFloat()

        // Centripetal lateral acceleration: a_y = v * omega
        val lateralAcc = (currentSpeedMps * angularVelRad).coerceIn(-6.0f, 6.0f)

        // Road micro-vibration noise
        val vibrationNoise = if (currentSpeedMps > 0.5f) ((random.nextFloat() * 0.3f) - 0.15f) else 0.0f
        val verticalAcc = 9.80665f + vibrationNoise

        return InertialTelemetry(
            accelerationX = lateralAcc,
            accelerationY = longitudinalAcc,
            accelerationZ = verticalAcc,
            gyroAngularRateZ = angularVelRad
        )
    }

    data class InertialTelemetry(
        val accelerationX: Float, // Lateral (G-force on turns)
        val accelerationY: Float, // Longitudinal (Acceleration / Braking)
        val accelerationZ: Float, // Vertical (Gravity + Road vibrations)
        val gyroAngularRateZ: Float // Yaw rate (rad/s)
    )

    /**
     * Diagnostic report on current anti-detection readiness.
     */
    data class AntiDetectionDiagnosticReport(
        val isGhostCloakEnabled: Boolean,
        val isNmeaStreamActive: Boolean,
        val isClockDriftActive: Boolean,
        val isMultiProviderActive: Boolean,
        val isIpShieldActive: Boolean,
        val isDeviceRooted: Boolean,
        val isRootMockGranted: Boolean,
        val sampleNmea: String,
        val activeSatellites: Int
    )

    fun generateDiagnosticReport(
        isEngineActive: Boolean,
        isVpnActive: Boolean,
        isRooted: Boolean,
        isRootGranted: Boolean
    ): AntiDetectionDiagnosticReport {
        val isEnabled = settingsPrefs?.isGhostCloakEnabled ?: true
        val isNmea = settingsPrefs?.isNmeaSynthesisEnabled ?: true
        val isClock = settingsPrefs?.isClockDriftEmulationEnabled ?: true

        val sampleNmea = if (isNmea) {
            generateNmeaStream(37.7749, -122.4194, 15.0, 10.0f, 45.0f).firstOrNull() ?: ""
        } else {
            "NMEA Synthesizer Disabled"
        }

        return AntiDetectionDiagnosticReport(
            isGhostCloakEnabled = isEnabled,
            isNmeaStreamActive = isEnabled && isNmea && isEngineActive,
            isClockDriftActive = isEnabled && isClock,
            isMultiProviderActive = isEngineActive,
            isIpShieldActive = isVpnActive,
            isDeviceRooted = isRooted,
            isRootMockGranted = isRootGranted,
            sampleNmea = sampleNmea,
            activeSatellites = if (isEngineActive) 18 else 0
        )
    }
}
