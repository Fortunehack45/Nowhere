import SwiftUI
import CoreLocation

struct SettingsSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var storage = StorageManager.shared
    @EnvironmentObject var updateChecker: AppUpdateChecker

    @State private var showEditSlot1 = false
    @State private var showEditSlot2 = false
    @State private var showEditSlot3 = false

    @State private var editName = ""
    @State private var editLat = ""
    @State private var editLon = ""
    @State private var currentEditingSlot = 1

    var body: some View {
        NavigationView {
            Form {
                // Section 1: Quick Destinations Widget Slots
                Section(header: Text("QUICK DESTINATION WIDGET SLOTS").foregroundColor(.red)) {
                    slotRow(slotNumber: 1, name: storage.slot1Name, lat: storage.slot1Lat, lon: storage.slot1Lon) {
                        openSlotEditor(slot: 1, name: storage.slot1Name, lat: storage.slot1Lat, lon: storage.slot1Lon)
                    }

                    slotRow(slotNumber: 2, name: storage.slot2Name, lat: storage.slot2Lat, lon: storage.slot2Lon) {
                        openSlotEditor(slot: 2, name: storage.slot2Name, lat: storage.slot2Lat, lon: storage.slot2Lon)
                    }

                    slotRow(slotNumber: 3, name: storage.slot3Name, lat: storage.slot3Lat, lon: storage.slot3Lon) {
                        openSlotEditor(slot: 3, name: storage.slot3Name, lat: storage.slot3Lat, lon: storage.slot3Lon)
                    }
                }

                // Section 2: Realism & Spoofing Enhancements
                Section(header: Text("REALISM & SIMULATION").foregroundColor(.red)) {
                    Toggle("Stationary GPS Jitter", isOn: $storage.randomizeJitter)

                    if storage.randomizeJitter {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(String(format: "Jitter Radius: %.1f m", storage.jitterRadiusMeters))
                                .font(.caption)
                                .foregroundColor(.gray)
                            Slider(value: $storage.jitterRadiusMeters, in: 0.5...10.0, step: 0.5)
                                .accentColor(.red)
                        }
                    }

                    Picker("Coordinate Precision", selection: $storage.truncateDecimals) {
                        Text("Full Precision").tag(-1)
                        Text("6 Decimals (High)").tag(6)
                        Text("4 Decimals (Street)").tag(4)
                    }

                    HStack {
                        Text("Default Altitude")
                        Spacer()
                        Text(String(format: "%.0f m", storage.defaultAltitude))
                            .foregroundColor(.gray)
                    }
                }

                // Section 3: Appearance & Units
                Section(header: Text("PREFERENCES").foregroundColor(.red)) {
                    Picker("Theme", selection: $storage.appTheme) {
                        Text("Dark Mode").tag("DARK")
                        Text("Light Mode").tag("LIGHT")
                        Text("System").tag("SYSTEM")
                    }

                    Picker("Distance Units", selection: $storage.distanceUnit) {
                        Text("Metric (km/h, m)").tag("METRIC")
                        Text("Imperial (mph, ft)").tag("IMPERIAL")
                    }

                    Toggle("Haptic Feedback", isOn: $storage.hapticFeedback)
                }

                // Section 4: Updates
                Section(header: Text("APP UPDATES").foregroundColor(.red)) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Installed Version")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.white)
                            Text("v\(updateChecker.currentVersion)")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(.gray)
                        }
                        Spacer()
                        if updateChecker.isChecking {
                            ProgressView()
                                .scaleEffect(0.75)
                        } else if updateChecker.updateAvailable {
                            VStack(alignment: .trailing, spacing: 3) {
                                Text("Update Available")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.red)
                                Text("v\(updateChecker.latestVersion)")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundColor(.red.opacity(0.8))
                            }
                        } else if updateChecker.latestVersion.isEmpty {
                            Text("Not checked")
                                .font(.system(size: 11))
                                .foregroundColor(.gray)
                        } else {
                            Text("Up to date ✓")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.green)
                        }
                    }

                    if updateChecker.updateAvailable, let url = updateChecker.releaseURL {
                        Link(destination: url) {
                            HStack {
                                Image(systemName: "arrow.down.circle.fill")
                                    .foregroundColor(.red)
                                Text("Download v\(updateChecker.latestVersion)")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.red)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                            }
                        }
                    }

                    Button(action: { updateChecker.checkForUpdate() }) {
                        HStack {
                            Image(systemName: "arrow.clockwise")
                                .foregroundColor(.gray)
                            Text(updateChecker.isChecking ? "Checking..." : "Check for Updates")
                                .foregroundColor(.gray)
                            Spacer()
                            if let lastChecked = updateChecker.lastCheckedAt {
                                Text(lastChecked, style: .relative)
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray.opacity(0.6))
                            }
                        }
                    }
                    .disabled(updateChecker.isChecking)
                }

                // Section 5: Developer Portfolio & Contact
                Section(header: Text("ABOUT NOWHERE").foregroundColor(.red)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Nowhere GPS Simulator for iOS")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("v\(updateChecker.currentVersion) • Precision Location Spoofing")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }

                    Link(destination: URL(string: "https://fortuneadebayo.space")!) {
                        HStack {
                            Text("Developer Website")
                            Spacer()
                            Text("fortuneadebayo.space")
                                .font(.caption)
                                .foregroundColor(.red)
                        }
                    }

                    Link(destination: URL(string: "https://t.me/nowhere_proxy")!) {
                        HStack {
                            Text("Telegram Channel")
                            Spacer()
                            Text("t.me/nowhere_proxy")
                                .font(.caption)
                                .foregroundColor(.red)
                        }
                    }

                    Link(destination: URL(string: "https://t.me/+vcmA7kOtLEw3ZjM0")!) {
                        HStack {
                            Text("Telegram Group")
                            Spacer()
                            Text("Join Community")
                                .font(.caption)
                                .foregroundColor(.red)
                        }
                    }

                    Link(destination: URL(string: "https://twitter.com/OnNerd_eth")!) {
                        HStack {
                            Text("Follow on X / Twitter")
                            Spacer()
                            Text("@OnNerd_eth")
                                .font(.caption)
                                .foregroundColor(.red)
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
            .sheet(isPresented: $showEditSlot1) {
                slotEditorSheet
            }
        }
        .preferredColorScheme(.dark)
    }

    private func slotRow(slotNumber: Int, name: String, lat: Double, lon: Double, onEdit: @escaping () -> Void) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Slot \(slotNumber): \(name)")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                Text(String(format: "%.4f°, %.4f°", lat, lon))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.gray)
            }
            Spacer()
            Button("Edit") {
                onEdit()
            }
            .font(.system(size: 12, weight: .bold))
            .foregroundColor(.red)
        }
    }

    private func openSlotEditor(slot: Int, name: String, lat: Double, lon: Double) {
        currentEditingSlot = slot
        editName = name
        editLat = String(lat)
        editLon = String(lon)
        showEditSlot1 = true
    }

    private var slotEditorSheet: some View {
        NavigationView {
            Form {
                Section(header: Text("Slot \(currentEditingSlot) Destination").foregroundColor(.gray)) {
                    TextField("Country / City Name", text: $editName)
                    TextField("Latitude (-90.0 to 90.0)", text: $editLat)
                        .keyboardType(.numbersAndPunctuation)
                    TextField("Longitude (-180.0 to 180.0)", text: $editLon)
                        .keyboardType(.numbersAndPunctuation)
                }
            }
            .navigationTitle("Edit Slot \(currentEditingSlot)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { showEditSlot1 = false }
                        .foregroundColor(.gray)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Save") {
                        if let lat = Double(editLat), let lon = Double(editLon), !editName.isEmpty {
                            switch currentEditingSlot {
                            case 1:
                                storage.slot1Name = editName
                                storage.slot1Lat = lat
                                storage.slot1Lon = lon
                            case 2:
                                storage.slot2Name = editName
                                storage.slot2Lat = lat
                                storage.slot2Lon = lon
                            case 3:
                                storage.slot3Name = editName
                                storage.slot3Lat = lat
                                storage.slot3Lon = lon
                            default: break
                            }
                            showEditSlot1 = false
                        }
                    }
                    .foregroundColor(.red)
                    .font(.headline)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
