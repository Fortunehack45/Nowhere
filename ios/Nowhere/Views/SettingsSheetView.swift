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
                Section(header: sectionHeader(title: "QUICK DESTINATION WIDGET SLOTS", systemImage: "target")) {
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
                Section(header: sectionHeader(title: "REALISM & SIMULATION", systemImage: "waveform.path.ecg")) {
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

                // Section 3: Anti-Detection Ghost Cloak Suite
                Section(header: sectionHeader(title: "ANTI-DETECTION & GHOST CLOAK", systemImage: "shield.checkered")) {
                    Toggle("Master Ghost Cloak", isOn: $storage.isGhostCloakEnabled)

                    if storage.isGhostCloakEnabled {
                        Toggle("NMEA-0183 Synthesizer", isOn: $storage.isNmeaSynthesisEnabled)
                        Toggle("Nanosecond Clock Drift (~18.5ns)", isOn: $storage.isClockDriftEmulationEnabled)
                        Toggle("Inertial G-Force Kinematics", isOn: $storage.isSensorKinematicsEnabled)
                    }
                }

                // Section 4: IP Privacy Shield & Auto-Sync
                Section(header: sectionHeader(title: "IP PRIVACY SHIELD & AUTO-SYNC", systemImage: "network.badge.shield.half.filled")) {
                    Toggle("Auto-Sync VPN with Mock GPS", isOn: $storage.isAutoVpnSyncEnabled)
                }

                // Section 5: Appearance & Units
                Section(header: sectionHeader(title: "PREFERENCES", systemImage: "paintpalette.fill")) {
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

                // Section 6: Updates
                Section(header: sectionHeader(title: "APP UPDATES", systemImage: "arrow.triangle.2.circlepath.circle.fill")) {
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

                // Section 7: Software Studio & Maker Branding
                Section(header: sectionHeader(title: "ABOUT & DEVELOPER", systemImage: "sparkles")) {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 14) {
                            // High-contrast rounded card for ÁYÁNFÈ logo
                            ZStack {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(Color.white)
                                    .frame(width: 58, height: 58)
                                    .shadow(color: Color.black.opacity(0.35), radius: 6, x: 0, y: 3)

                                if let uiImage = UIImage(named: "ayanfe_logo") ?? UIImage(contentsOfFile: Bundle.main.path(forResource: "ayanfe_logo", ofType: "png") ?? "") {
                                    Image(uiImage: uiImage)
                                        .resizable()
                                        .aspectRatio(contentMode: .fit)
                                        .frame(width: 50, height: 50)
                                        .padding(4)
                                } else {
                                    AsyncImage(url: URL(string: "https://iili.io/noehpqb.png")) { phase in
                                        if let image = phase.image {
                                            image
                                                .resizable()
                                                .aspectRatio(contentMode: .fit)
                                                .frame(width: 50, height: 50)
                                                .padding(4)
                                        } else {
                                            Image(systemName: "app.badge.checkmark.fill")
                                                .font(.system(size: 26))
                                                .foregroundColor(.red)
                                        }
                                    }
                                }
                            }

                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text("Made by Àyànfẹ́")
                                        .font(.system(size: 15, weight: .black, design: .rounded))
                                        .foregroundColor(.white)

                                    HStack(spacing: 3) {
                                        Image(systemName: "checkmark.seal.fill")
                                            .font(.system(size: 9))
                                            .foregroundColor(.green)
                                        Text("STUDIO")
                                            .font(.system(size: 8, weight: .black, design: .rounded))
                                            .foregroundColor(.green)
                                    }
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 2)
                                    .background(Color.green.opacity(0.15))
                                    .cornerRadius(4)
                                }

                                Text("Àyànfẹ́ Software Studio • Mobile & Cyber-Security")
                                    .font(.system(size: 10.5, weight: .medium))
                                    .foregroundColor(.gray)

                                Text("Nowhere iOS v\(updateChecker.currentVersion) • Production Build")
                                    .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                                    .foregroundColor(.red.opacity(0.9))
                            }
                        }
                        .padding(.vertical, 4)

                        // Privacy & Zero-Telemetry Guarantee Pill
                        HStack(spacing: 8) {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 14))
                                .foregroundColor(.green)

                            VStack(alignment: .leading, spacing: 1) {
                                Text("Zero-Telemetry Privacy Guarantee")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                                Text("100% on-device spoofing. No tracking, logs, or analytics.")
                                    .font(.system(size: 9.5))
                                    .foregroundColor(.gray)
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(Color.white.opacity(0.04))
                        .cornerRadius(10)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.08), lineWidth: 0.8))
                    }

                    Link(destination: URL(string: "https://fortuneadebayo.space")!) {
                        HStack {
                            ZStack {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Color.red.opacity(0.15))
                                    .frame(width: 26, height: 26)
                                Image(systemName: "globe")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.red)
                            }
                            Text("Studio Portfolio")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(.white)
                            Spacer()
                            Text("fortuneadebayo.space")
                                .font(.system(size: 11))
                                .foregroundColor(.red)
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.red.opacity(0.7))
                        }
                    }

                    Link(destination: URL(string: "https://t.me/nowhere_proxy")!) {
                        HStack {
                            ZStack {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Color.blue.opacity(0.15))
                                    .frame(width: 26, height: 26)
                                Image(systemName: "paperplane.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.blue)
                            }
                            Text("Telegram Announcements")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(.white)
                            Spacer()
                            Text("t.me/nowhere_proxy")
                                .font(.system(size: 11))
                                .foregroundColor(.blue)
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.blue.opacity(0.7))
                        }
                    }

                    Link(destination: URL(string: "https://t.me/+vcmA7kOtLEw3ZjM0")!) {
                        HStack {
                            ZStack {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Color.blue.opacity(0.15))
                                    .frame(width: 26, height: 26)
                                Image(systemName: "person.2.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.blue)
                            }
                            Text("Community Group")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(.white)
                            Spacer()
                            Text("Join Telegram")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.blue)
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.blue.opacity(0.7))
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        presentationMode.wrappedValue.dismiss()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 16))
                            Text("Done")
                                .font(.system(size: 14, weight: .bold))
                        }
                        .foregroundColor(.red)
                    }
                }
            }
            .sheet(isPresented: $showEditSlot1) {
                slotEditorSheet
            }
        }
        .preferredColorScheme(.dark)
    }

    private func sectionHeader(title: String, systemImage: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.red)
            Text(title)
                .font(.system(size: 11, weight: .bold, design: .rounded))
                .tracking(0.8)
                .foregroundColor(.red)
        }
    }

    private func slotRow(slotNumber: Int, name: String, lat: Double, lon: Double, onEdit: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.red.opacity(0.15))
                    .frame(width: 32, height: 32)
                Image(systemName: "mappin.and.ellipse")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.red)
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text("SLOT \(slotNumber)")
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1.5)
                        .background(Color.red.opacity(0.2))
                        .foregroundColor(.red)
                        .cornerRadius(4)

                    Text(name.isEmpty ? "Unassigned" : name)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                }

                Text(String(format: "%.4f°, %.4f°", lat, lon))
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundColor(.gray)
            }

            Spacer()

            Button(action: {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                onEdit()
            }) {
                HStack(spacing: 3) {
                    Image(systemName: "pencil")
                        .font(.system(size: 10, weight: .bold))
                    Text("Edit")
                        .font(.system(size: 12, weight: .bold))
                }
                .foregroundColor(.red)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.red.opacity(0.12))
                .cornerRadius(8)
            }
            .buttonStyle(BorderlessButtonStyle())
        }
        .padding(.vertical, 2)
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
