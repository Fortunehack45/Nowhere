import SwiftUI

struct HotspotTetheringSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var hotspot = HotspotLocationManager.shared
    @ObservedObject var engine = LocationSimulationEngine.shared

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    // Header Card
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Image(systemName: "wifi.router")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(.red)

                            VStack(alignment: .leading, spacing: 2) {
                                Text("HOTSPOT GPS SYNC (BETA)")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(.white)
                                Text("Broadcast spoofed coordinates over Wi-Fi / NMEA")
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                            }
                            Spacer()

                            Text(hotspot.isServerRunning ? "BROADCASTING" : "STANDBY")
                                .font(.system(size: 10, weight: .black))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(hotspot.isServerRunning ? Color.green.opacity(0.2) : Color.gray.opacity(0.2))
                                .foregroundColor(hotspot.isServerRunning ? .green : .gray)
                                .cornerRadius(8)
                        }

                        Divider().background(Color.white.opacity(0.1))

                        Toggle(isOn: Binding(
                            get: { hotspot.isServerRunning },
                            set: { isRunning in
                                if isRunning {
                                    hotspot.startBroadcast()
                                } else {
                                    hotspot.stopBroadcast()
                                }
                            }
                        )) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Enable NMEA / HTTP Broadcast")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Allows PCs, laptops, and second phones to receive GPS")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                        }
                        .toggleStyle(SwitchToggleStyle(tint: .red))
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))

                    // Connection Endpoint Card
                    VStack(alignment: .leading, spacing: 12) {
                        Text("CONNECTION ENDPOINT")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.red)

                        HStack {
                            Text("Local Stream URL:")
                                .font(.system(size: 12))
                                .foregroundColor(.gray)
                            Spacer()
                            Text("http://\(hotspot.localIpAddress):\(hotspot.serverPort)/nmea")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.green)
                        }

                        HStack {
                            Text("Connected Clients:")
                                .font(.system(size: 12))
                                .foregroundColor(.gray)
                            Spacer()
                            Text("\(hotspot.connectedClientsCount) Devices")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.white)
                        }

                        HStack {
                            Text("Current Transmitted Lat/Lon:")
                                .font(.system(size: 12))
                                .foregroundColor(.gray)
                            Spacer()
                            Text(String(format: "%.4f, %.4f", engine.currentCoordinate.latitude, engine.currentCoordinate.longitude))
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.red)
                        }
                    }
                    .padding()
                    .background(Color(white: 0.12))
                    .cornerRadius(16)
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))
                }
                .padding()
            }
            .background(Color(white: 0.08).ignoresSafeArea())
            .navigationTitle("Hotspot GPS Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
        }
    }
}
