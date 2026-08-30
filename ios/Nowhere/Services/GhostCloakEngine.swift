import Foundation
import CoreLocation

/// Ghost Cloak Anti-Detection Engine for iOS.
/// Synthesizes authentic NMEA-0183 hardware sentences, nanosecond clock uncertainty drift,
/// genuine GNSS constellation distributions, and physical kinematics.
class GhostCloakEngine {

    static let shared = GhostCloakEngine()

    struct DiagnosticReport {
        let isGhostCloakEnabled: Bool
        let isNmeaStreamActive: Bool
        let isClockDriftActive: Bool
        let isMultiProviderActive: Bool
        let isIpShieldActive: Bool
        let sampleNmea: String
    }

    struct InertialTelemetry {
        let accelerationX: Double // Forward/backward (m/s²)
        let accelerationY: Double // Lateral centripetal (m/s²)
        let accelerationZ: Double // Vertical + Gravity (~9.8 m/s²)
        let angularRateYaw: Double // Degrees/second
    }

    private var lastBearing: Double = 0.0
    private var lastSpeedMps: Double = 0.0

    // MARK: - NMEA 0183 Hardware Stream Generation

    func generateNmeaStream(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speedMps: Double,
        bearingDeg: Double,
        timestamp: Date = Date()
    ) -> [String] {
        var sentences: [String] = []

        let calendar = Calendar(identifier: .gregorian)
        var utcCal = calendar
        utcCal.timeZone = TimeZone(secondsFromGMT: 0)!
        let comps = utcCal.dateComponents([.hour, .minute, .second, .nanosecond, .day, .month, .year], from: timestamp)

        let hour = comps.hour ?? 0
        let minute = comps.minute ?? 0
        let second = comps.second ?? 0
        let millis = (comps.nanosecond ?? 0) / 10_000_000

        let timeStr = String(format: "%02d%02d%02d.%02d", hour, minute, second, millis)

        let day = comps.day ?? 1
        let month = comps.month ?? 1
        let yearShort = (comps.year ?? 2026) % 100
        let dateStr = String(format: "%02d%02d%02d", day, month, yearShort)

        let latNmea = toNmeaDegreesMinutes(coordinate: latitude, isLatitude: true)
        let lonNmea = toNmeaDegreesMinutes(coordinate: longitude, isLatitude: false)
        let latHem = latitude >= 0 ? "N" : "S"
        let lonHem = longitude >= 0 ? "E" : "W"

        let speedKnots = speedMps * 1.94384
        let speedKnotsStr = String(format: "%.1f", speedKnots)
        let bearingStr = String(format: "%.1f", bearingDeg)
        let altStr = String(format: "%.1f", altitude)

        // 1. $GPRMC (Recommended Minimum Navigation Information)
        let rmcPayload = "GPRMC,\(timeStr),A,\(latNmea),\(latHem),\(lonNmea),\(lonHem),\(speedKnotsStr),\(bearingStr),\(dateStr),,,A"
        sentences.append(formatNmeaSentence(payload: rmcPayload))

        // 2. $GPGGA (Global Positioning System Fix Data)
        let satCount = 14 + (Int(timestamp.timeIntervalSince1970) % 5) // 14 to 18 sats
        let hdop = 0.8 + (Double(Int(timestamp.timeIntervalSince1970) % 4) * 0.1)
        let ggaPayload = "GPGGA,\(timeStr),\(latNmea),\(latHem),\(lonNmea),\(lonHem),1,\(satCount),\(String(format: "%.1f", hdop)),\(altStr),M,0.0,M,,"
        sentences.append(formatNmeaSentence(payload: ggaPayload))

        // 3. $GPGSA (DOP and Active Satellites)
        let gsaPayload = "GPGSA,A,3,03,08,11,14,17,19,22,28,01,07,13,24,1.4,\(String(format: "%.1f", hdop)),1.1"
        sentences.append(formatNmeaSentence(payload: gsaPayload))

        // 4. $GPGSV (GPS Satellites in View - Part 1 & 2)
        let gsv1Payload = "GPGSV,3,1,12,03,45,120,42,08,60,045,46,11,30,210,38,14,15,310,34"
        let gsv2Payload = "GPGSV,3,2,12,17,70,180,48,19,25,090,36,22,50,270,44,28,40,330,41"
        let gsv3Payload = "GPGSV,3,3,12,01,10,030,29,07,85,150,50,13,35,240,39,24,18,060,32"
        sentences.append(formatNmeaSentence(payload: gsv1Payload))
        sentences.append(formatNmeaSentence(payload: gsv2Payload))
        sentences.append(formatNmeaSentence(payload: gsv3Payload))

        // 5. $GLGSV (GLONASS Satellites in View)
        let glgsvPayload = "GLGSV,2,1,06,65,40,110,39,66,75,030,45,71,20,190,33,72,55,250,42"
        sentences.append(formatNmeaSentence(payload: glgsvPayload))

        return sentences
    }

    // MARK: - Nanosecond Clock Uncertainty & Jitter

    func synthesizeClockUncertainty() -> Double {
        // Authentic GNSS chipset uncertainty ranges from 15.0 to 38.0 nanoseconds
        return 18.0 + (Double.random(in: 0...1) * 14.5)
    }

    // MARK: - Physical Inertial Kinematics

    func computeInertialTelemetry(speedMps: Double, bearingDeg: Double, deltaSeconds: Double) -> InertialTelemetry {
        let deltaBearing = normalizeBearingDelta(bearingDeg - lastBearing)
        let angularRateYaw = deltaSeconds > 0 ? (deltaBearing / deltaSeconds) : 0.0

        let yawRadPerSec = angularRateYaw * .pi / 180.0
        let centripetalAccY = speedMps * yawRadPerSec

        let accelX = deltaSeconds > 0 ? ((speedMps - lastSpeedMps) / deltaSeconds) : 0.0

        // Vertical gravity + subtle road micro-vibration noise
        let vibrationNoise = (Double.random(in: -1...1) * 0.12)
        let accelZ = 9.80665 + vibrationNoise

        lastBearing = bearingDeg
        lastSpeedMps = speedMps

        return InertialTelemetry(
            accelerationX: accelX,
            accelerationY: centripetalAccY,
            accelerationZ: accelZ,
            angularRateYaw: angularRateYaw
        )
    }

    // MARK: - Diagnostics

    func generateDiagnosticReport(isSimulating: Bool, isIpShieldActive: Bool) -> DiagnosticReport {
        let storage = StorageManager.shared
        let isMaster = storage.isGhostCloakEnabled
        let isNmea = storage.isNmeaSynthesisEnabled
        let isClock = storage.isClockDriftEmulationEnabled

        let sampleSentences = generateNmeaStream(
            latitude: 37.7749,
            longitude: -122.4194,
            altitude: 15.0,
            speedMps: 10.0,
            bearingDeg: 45.0
        )

        return DiagnosticReport(
            isGhostCloakEnabled: isMaster,
            isNmeaStreamActive: isMaster && isNmea,
            isClockDriftActive: isMaster && isClock,
            isMultiProviderActive: isMaster,
            isIpShieldActive: isIpShieldActive,
            sampleNmea: sampleSentences.prefix(2).joined(separator: "\n")
        )
    }

    // MARK: - Helper Methods

    private func formatNmeaSentence(payload: String) -> String {
        var checksum: UInt8 = 0
        for byte in payload.utf8 {
            checksum ^= byte
        }
        let hex = String(format: "%02X", checksum)
        return "$\(payload)*\(hex)"
    }

    private func toNmeaDegreesMinutes(coordinate: Double, isLatitude: Bool) -> String {
        let absVal = abs(coordinate)
        let degrees = Int(absVal)
        let minutes = (absVal - Double(degrees)) * 60.0

        if isLatitude {
            return String(format: "%02d%07.4f", degrees, minutes)
        } else {
            return String(format: "%03d%07.4f", degrees, minutes)
        }
    }

    private func normalizeBearingDelta(_ delta: Double) -> Double {
        var d = delta
        while d > 180 { d -= 360 }
        while d < -180 { d += 360 }
        return d
    }
}
