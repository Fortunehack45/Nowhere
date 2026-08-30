import SwiftUI

struct AntiDetectionSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var storage = StorageManager.shared
    @StateObject private var ghostEngine = GhostCloakEngine.shared
    @State private var liveNmeaStream: String = "$GPRMC,120530.00,A,3746.4940,N,12225.1640,W,10.5,45.0,300826,,,A*7C\n$GPGGA,120530.00,3746.4940,N,12225.1640,W,1,16,0.9,15.0,M,0.0,M,,*5A"
    @State private var timer: Timer? = nil

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    // Header Card
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "shield.lefthalf.filled.badge.checkmark")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(.red)

                            VStack(alignment: .leading, spacing: 2) {
                                Text("GHOST CLOAK STEALTH SUITE")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(.white)
                                Text("Anti-detection & hardware telemetry synthesis")
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                            }
                            Spacer()

                            Text(storage.isGhostCloakEnabled ? "CLOAKED" : "RAW GPS")
                                .font(.system(size: 10, weight: .black))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(storage.isGhostCloakEnabled ? Color.red.opacity(0.2) : Color.gray.opacity(0.2))
                                .foregroundColor(storage.isGhostCloakEnabled ? .red : .gray)
                                .cornerRadius(8)
                        }

                        Divider().background(Color.white.opacity(0.1))

                        Toggle(isOn: $storage.isGhostCloakEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Master Ghost Cloak")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Prevents detection by third-party apps")
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                            }
                        }
                        .toggleStyle(SwitchToggleStyle(tint: .red))
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))

                    // Granular Modules
                    VStack(alignment: .leading, spacing: 14) {
                        Text("TELEMETRY ENGINES")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.red)

                        Toggle(isOn: $storage.isNmeaSynthesisEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("NMEA-0183 Synthesizer")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Emulates $GPRMC, $GPGGA, $GPGSV hardware sentences")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                        }
                        .disabled(!storage.isGhostCloakEnabled)
                        .toggleStyle(SwitchToggleStyle(tint: .red))

                        Toggle(isOn: $storage.isClockDriftEmulationEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Monotonic Clock Uncertainty (~18.5 ns)")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Microsecond boot clock jitter prevents timing heuristics")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                        }
                        .disabled(!storage.isGhostCloakEnabled)
                        .toggleStyle(SwitchToggleStyle(tint: .red))

                        Toggle(isOn: $storage.isSensorKinematicsEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Inertial G-Force Kinematics")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Calculates realistic centripetal force and road noise")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                        }
                        .disabled(!storage.isGhostCloakEnabled)
                        .toggleStyle(SwitchToggleStyle(tint: .red))
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))

                    // Live Diagnostics
                    VStack(alignment: .leading, spacing: 10) {
                        Text("SYSTEM STEALTH STATUS")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.red)

                        diagnosticRow(
                            icon: "antenna.radiowaves.left.and.right",
                            title: "Hardware Location Synthesis",
                            status: storage.isGhostCloakEnabled ? "100% Active" : "Disabled",
                            isActive: storage.isGhostCloakEnabled
                        )

                        diagnosticRow(
                            icon: "clock.arrow.circlepath",
                            title: "Nanosecond Time Drift",
                            status: storage.isGhostCloakEnabled && storage.isClockDriftEmulationEnabled ? "Synchronized (~22.4 ns)" : "Off",
                            isActive: storage.isGhostCloakEnabled && storage.isClockDriftEmulationEnabled
                        )

                        diagnosticRow(
                            icon: "network.badge.shield.half.filled",
                            title: "IP Geolocation Coherence",
                            status: IpNodeManager.shared.connectionState != .disconnected ? "Encrypted Shield Active" : "Direct IP",
                            isActive: IpNodeManager.shared.connectionState != .disconnected
                        )
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))

                    // Live Terminal Feed Box
                    VStack(alignment: .leading, spacing: 8) {
                        Text("LIVE NMEA HARDWARE STREAM")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.red)

                        Text(liveNmeaStream)
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundColor(.green)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.black)
                            .cornerRadius(10)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.15), lineWidth: 1))
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                }
                .padding()
            }
            .background(Color(white: 0.08).ignoresSafeArea())
            .navigationTitle("Anti-Detection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
            .onAppear {
                startNmeaFeed()
            }
            .onDisappear {
                timer?.invalidate()
            }
        }
    }

    private func diagnosticRow(icon: String, title: String, status: String, isActive: Bool) -> some View {
        HStack {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundColor(isActive ? .green : .orange)
                .frame(width: 20)

            Text(title)
                .font(.system(size: 12))
                .foregroundColor(.white)

            Spacer()

            Text(status)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(isActive ? .green : .orange)
        }
    }

    private func startNmeaFeed() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            let sentences = ghostEngine.generateNmeaStream(
                latitude: LocationSimulationEngine.shared.currentCoordinate.latitude,
                longitude: LocationSimulationEngine.shared.currentCoordinate.longitude,
                altitude: 15.0,
                speedMps: (LocationSimulationEngine.shared.currentSpeedKmh * 1000) / 3600,
                bearingDeg: LocationSimulationEngine.shared.currentBearing
            )
            liveNmeaStream = sentences.prefix(2).joined(separator: "\n")
        }
    }
}
