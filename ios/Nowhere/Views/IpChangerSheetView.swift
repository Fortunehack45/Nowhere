import SwiftUI
import CoreLocation

struct IpChangerSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var ipManager = IpNodeManager.shared
    @ObservedObject var storage = StorageManager.shared

    var body: some View {
        NavigationView {
            VStack(spacing: 14) {
                // Header Auto-Sync Toggle Card
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Image(systemName: "network.badge.shield.half.filled")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(.red)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("IP PRIVACY SHIELD")
                                .font(.system(size: 14, weight: .black))
                                .foregroundColor(.white)
                            Text("Route IP traffic matching GPS coordinate node")
                                .font(.system(size: 11))
                                .foregroundColor(.gray)
                        }
                        Spacer()
                    }

                    Divider().background(Color.white.opacity(0.1))

                    Toggle(isOn: $ipManager.isAutoSyncEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Auto-Sync with Mock GPS")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.white)
                            Text("Ignites VPN on simulation start and selects closest server")
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
                .padding(.horizontal)

                // Current Connected Status
                HStack {
                    switch ipManager.connectionState {
                    case .connected(let node):
                        HStack(spacing: 8) {
                            Circle().fill(Color.green).frame(width: 8, height: 8)
                            Text("Connected: \(node.flagEmoji) \(node.cityName) (\(node.pingLatencyMs)ms)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.green)
                            Spacer()
                            Button("Disconnect") {
                                ipManager.disconnect()
                            }
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.red)
                        }
                    case .connecting(let node):
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.7)
                            Text("Connecting to \(node.cityName)...")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.orange)
                            Spacer()
                        }
                    case .disconnected:
                        HStack(spacing: 8) {
                            Circle().fill(Color.gray).frame(width: 8, height: 8)
                            Text("Direct Internet (Shield Off)")
                                .font(.system(size: 12))
                                .foregroundColor(.gray)
                            Spacer()
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)

                // Available IP Privacy Nodes List
                List(ipManager.availableNodes) { node in
                    let isSelected: Bool = {
                        if case .connected(let activeNode) = ipManager.connectionState {
                            return activeNode.id == node.id
                        }
                        return false
                    }()

                    Button(action: {
                        ipManager.connect(to: node)
                    }) {
                        HStack(spacing: 12) {
                            Text(node.flagEmoji)
                                .font(.system(size: 24))

                            VStack(alignment: .leading, spacing: 2) {
                                Text(node.cityName)
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(.white)
                                Text(node.countryName)
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                            }

                            Spacer()

                            HStack(spacing: 4) {
                                Image(systemName: "cellularbars")
                                    .font(.system(size: 10))
                                Text("\(node.pingLatencyMs) ms")
                                    .font(.system(size: 11, design: .monospaced))
                            }
                            .foregroundColor(node.pingLatencyMs < 35 ? .green : .yellow)

                            if isSelected {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .listRowBackground(isSelected ? Color.red.opacity(0.12) : Color(white: 0.12))
                }
                .listStyle(InsetGroupedListStyle())
            }
            .background(Color(white: 0.08).ignoresSafeArea())
            .navigationTitle("IP Privacy Shield")
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
