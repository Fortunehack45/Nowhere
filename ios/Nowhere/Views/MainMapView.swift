import SwiftUI
import MapKit

struct MainMapView: View {

    @StateObject private var engine = LocationSimulationEngine.shared
    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )

    @State private var pinnedLocation: CLLocationCoordinate2D = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    @State private var waypoints: [RoutePoint] = []
    @State private var selectedTab: String = "FIXED"
    @State private var speedKmh: Double = 60.0
    @State private var isLooping: Bool = true
    @State private var transportMode: TransportMode = .vehicle
    @State private var showShareSheet: Bool = false
    @State private var gpxExportURL: URL? = nil

    var body: some View {
        ZStack(alignment: .bottom) {
            // Map Canvas
            Map(coordinateRegion: $region, annotationItems: getAnnotations()) { item in
                MapAnnotation(coordinate: item.coordinate) {
                    VStack(spacing: 0) {
                        Image(systemName: item.isCurrent ? "location.fill" : "mappin.circle.fill")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(item.isCurrent ? Color.red : Color.blue)
                            .shadow(color: .black.opacity(0.3), radius: 4)

                        Text(item.title)
                            .font(.system(size: 10, weight: .bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.75))
                            .foregroundColor(.white)
                            .cornerRadius(6)
                    }
                }
            }
            .ignoresSafeArea()
            .preferredColorScheme(.dark)

            // Top Header & Mode Tabs
            VStack(spacing: 8) {
                // Top App Bar
                HStack {
                    Image(systemName: "location.north.circle.fill")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.red)

                    Text("NOWHERE")
                        .font(.system(size: 16, weight: .black))
                        .kerning(1.2)
                        .foregroundColor(.white)

                    Spacer()

                    // Active Pill Status
                    Text(engine.isSimulating ? "● ACTIVE" : "STANDBY")
                        .font(.system(size: 10, weight: .bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(engine.isSimulating ? Color.red.opacity(0.25) : Color.gray.opacity(0.25))
                        .foregroundColor(engine.isSimulating ? .red : .gray)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(engine.isSimulating ? Color.red : Color.gray, lineWidth: 1)
                        )
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(Color(red: 0.1, green: 0.1, blue: 0.12).opacity(0.95))
                .cornerRadius(16)
                .padding(.horizontal, 16)
                .padding(.top, 40)

                // Segmented Mode Picker
                HStack(spacing: 6) {
                    modeButton(title: "Fixed", id: "FIXED")
                    modeButton(title: "Route", id: "ROUTE")
                    modeButton(title: "GPX Export", id: "GPX")
                }
                .padding(4)
                .background(Color.black.opacity(0.6))
                .cornerRadius(14)
                .padding(.horizontal, 16)

                Spacer()
            }

            // Bottom Glassmorphic Control HUD
            VStack(spacing: 12) {
                if selectedTab == "FIXED" {
                    fixedControlsView
                } else if selectedTab == "ROUTE" {
                    routeControlsView
                } else {
                    gpxExportView
                }
            }
            .padding(16)
            .background(
                Color(red: 0.11, green: 0.11, blue: 0.13)
                    .opacity(0.96)
                    .background(.ultraThinMaterial)
            )
            .cornerRadius(24)
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.12), lineWidth: 1)
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .sheet(isPresented: $showShareSheet) {
            if let url = gpxExportURL {
                ShareSheet(activityItems: [url])
            }
        }
    }

    private func modeButton(title: String, id: String) -> some View {
        Button(action: { selectedTab = id }) {
            Text(title)
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(selectedTab == id ? .white : .gray)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(selectedTab == id ? Color.red : Color.clear)
                .cornerRadius(10)
        }
    }

    // MARK: - Subviews

    private var fixedControlsView: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("TELEPORT COORDINATES")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.gray)
                    Text(String(format: "%.5f°, %.5f°", pinnedLocation.latitude, pinnedLocation.longitude))
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }
                Spacer()
            }

            HStack(spacing: 10) {
                Button(action: {
                    engine.startFixed(coordinate: pinnedLocation)
                }) {
                    HStack {
                        Image(systemName: "location.fill")
                        Text("Inject Location")
                    }
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red)
                    .cornerRadius(14)
                }

                if engine.isSimulating {
                    Button(action: { engine.stop() }) {
                        Text("Stop")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.red)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(14)
                    }
                }
            }
        }
    }

    private var routeControlsView: some View {
        VStack(spacing: 12) {
            HStack {
                Text("\(waypoints.count) Waypoints Plotted")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white)

                Spacer()

                Text(String(format: "%.0f KM/H", speedKmh))
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.red)
            }

            Slider(value: $speedKmh, in: 5...160, step: 5)
                .accentColor(.red)

            HStack(spacing: 10) {
                Button(action: {
                    if engine.isSimulating {
                        engine.togglePause()
                    } else {
                        engine.startRoute(waypoints: waypoints, speedKmh: speedKmh, isLooping: isLooping, mode: transportMode)
                    }
                }) {
                    HStack {
                        Image(systemName: engine.isSimulating ? (engine.isPaused ? "play.fill" : "pause.fill") : "play.fill")
                        Text(engine.isSimulating ? (engine.isPaused ? "Resume" : "Pause") : "Start Route")
                    }
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red)
                    .cornerRadius(14)
                }

                Button(action: {
                    waypoints.removeAll()
                    engine.stop()
                }) {
                    Text("Clear")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.gray)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(Color.white.opacity(0.08))
                        .cornerRadius(14)
                }
            }
        }
    }

    private var gpxExportView: some View {
        VStack(spacing: 12) {
            Text("Export your route as a standard Apple GPX track to simulate on iOS via Xcode or LocationSimulator.")
                .font(.system(size: 12))
                .foregroundColor(.gray)
                .multilineTextAlignment(.leading)

            Button(action: {
                if let url = GPXManager.shared.exportGPXFile(waypoints: waypoints.isEmpty ? [RoutePoint(latitude: pinnedLocation.latitude, longitude: pinnedLocation.longitude)] : waypoints) {
                    gpxExportURL = url
                    showShareSheet = true
                }
            }) {
                HStack {
                    Image(systemName: "square.and.arrow.up")
                    Text("Export / AirDrop GPX")
                }
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.red)
                .cornerRadius(14)
            }
        }
    }

    private func getAnnotations() -> [MapItem] {
        var list: [MapItem] = []
        if engine.isSimulating {
            list.append(MapItem(id: "current", coordinate: engine.currentCoordinate, title: "Injected GPS", isCurrent: true))
        } else {
            list.append(MapItem(id: "pinned", coordinate: pinnedLocation, title: "Pinned Location", isCurrent: false))
        }
        for (i, wp) in waypoints.enumerated() {
            list.append(MapItem(id: wp.id, coordinate: wp.coordinate, title: "Point \(i + 1)", isCurrent: false))
        }
        return list
    }
}

struct MapItem: Identifiable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let title: String
    let isCurrent: Bool
}

struct ShareSheet: UIViewControllerRepresentable {
    var activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
