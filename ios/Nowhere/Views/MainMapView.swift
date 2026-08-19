import SwiftUI
import MapKit

struct MainMapView: View {

    @StateObject private var engine = LocationSimulationEngine.shared
    @StateObject private var searchService = SearchService.shared
    @StateObject private var storage = StorageManager.shared
    @EnvironmentObject var updateChecker: AppUpdateChecker

    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )

    @State private var pinnedLocation: CLLocationCoordinate2D = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    @State private var waypoints: [RoutePoint] = []
    @State private var selectedTab: String = "FIXED" // FIXED, ROUTE, JOYSTICK, GPX
    @State private var speedKmh: Double = 60.0
    @State private var isLooping: Bool = true
    @State private var transportMode: TransportMode = .vehicle

    // Search state
    @State private var searchQuery: String = ""
    @State private var isSearchFocused: Bool = false

    // Sheets
    @State private var showFavoritesSheet: Bool = false
    @State private var showRoutesSheet: Bool = false
    @State private var showSettingsSheet: Bool = false
    @State private var showSaveRouteDialog: Bool = false
    @State private var newRouteName: String = ""
    @State private var showShareSheet: Bool = false
    @State private var gpxExportURL: URL? = nil

    // Update banner dismiss state
    @State private var updateBannerDismissed: Bool = false

    // Location name
    @State private var activeLocationName: String = "San Francisco"

    var body: some View {
        ZStack(alignment: .bottom) {
            // Map Canvas
            Map(coordinateRegion: $region, annotationItems: getAnnotations()) { item in
                MapAnnotation(coordinate: item.coordinate) {
                    VStack(spacing: 0) {
                        Image(systemName: item.isCurrent ? "location.fill" : (item.id == "pinned" ? "mappin.circle.fill" : "circle.fill"))
                            .font(.system(size: item.isCurrent ? 26 : 20, weight: .bold))
                            .foregroundColor(item.isCurrent ? Color.red : (item.id == "pinned" ? Color.red : Color.blue))
                            .shadow(color: .black.opacity(0.4), radius: 4)

                        Text(item.title)
                            .font(.system(size: 9, weight: .bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.8))
                            .foregroundColor(.white)
                            .cornerRadius(6)
                    }
                }
            }
            .ignoresSafeArea()
            .preferredColorScheme(storage.appTheme == "LIGHT" ? .light : .dark)

            // Top Header, Search Bar & Mode Switcher
            VStack(spacing: 6) {
                topNavigationBar
                searchBarView

                if isSearchFocused && (!searchService.searchResults.isEmpty || !storage.searchHistory.isEmpty) {
                    searchResultsDropdown
                }

                segmentedModePicker

                // Update Available Banner
                if updateChecker.updateAvailable && !updateBannerDismissed {
                    updateAvailableBanner
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                Spacer()
            }

            // Floating Joystick (when JOYSTICK tab is active)
            if selectedTab == "JOYSTICK" {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        JoystickOverlayView(
                            currentCoordinate: $pinnedLocation,
                            speedKmh: speedKmh,
                            isEnabled: true,
                            onCoordinateUpdated: { newCoord in
                                if engine.isSimulating {
                                    engine.startFixed(coordinate: newCoord)
                                }
                            }
                        )
                        .padding(.trailing, 24)
                        .padding(.bottom, 220)
                    }
                }
            }

            // Bottom Control Card HUD
            bottomControlCard
        }
        .sheet(isPresented: $showFavoritesSheet) {
            FavoritesSheetView { coord, name in
                teleportTo(coord: coord, name: name)
            }
        }
        .sheet(isPresented: $showRoutesSheet) {
            SavedRoutesSheetView { pts, speed, name in
                self.waypoints = pts
                self.speedKmh = speed
                self.selectedTab = "ROUTE"
                engine.startRoute(waypoints: pts, speedKmh: speed, isLooping: isLooping, mode: transportMode)
            }
        }
        .sheet(isPresented: $showSettingsSheet) {
            SettingsSheetView()
        }
        .sheet(isPresented: $showShareSheet) {
            if let url = gpxExportURL {
                ShareSheet(activityItems: [url])
            }
        }
        .alert("Save Route", isPresented: $showSaveRouteDialog) {
            TextField("Route Name", text: $newRouteName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                if !newRouteName.isEmpty {
                    storage.saveRoute(name: newRouteName, waypoints: waypoints, speed: speedKmh)
                    newRouteName = ""
                }
            }
        } message: {
            Text("Enter a title for this \(waypoints.count)-waypoint route.")
        }
    }

    // MARK: - Top Navigation Bar
    private var topNavigationBar: some View {
        HStack {
            Image(systemName: "location.north.circle.fill")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.red)

            Text("NOWHERE")
                .font(.system(size: 16, weight: .black))
                .kerning(1.2)
                .foregroundColor(.white)

            Spacer()

            // Active Glowing Country/Destination Status Pill
            Text(engine.isSimulating ? "📍 \(activeLocationName.uppercased())" : "STANDBY")
                .font(.system(size: 10, weight: .bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(engine.isSimulating ? Color.red.opacity(0.25) : Color.gray.opacity(0.25))
                .foregroundColor(engine.isSimulating ? .red : .gray)
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(engine.isSimulating ? Color.red : Color.gray, lineWidth: 1)
                )

            // Top action buttons: Favorites, Routes, Settings
            HStack(spacing: 8) {
                Button(action: { showFavoritesSheet = true }) {
                    Image(systemName: "star.fill")
                        .font(.system(size: 14))
                        .foregroundColor(.white)
                        .padding(8)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Circle())
                }

                Button(action: { showRoutesSheet = true }) {
                    Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.system(size: 14))
                        .foregroundColor(.white)
                        .padding(8)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Circle())
                }

                Button(action: { showSettingsSheet = true }) {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 14))
                        .foregroundColor(.white)
                        .padding(8)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Circle())
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(red: 0.1, green: 0.1, blue: 0.12).opacity(0.95))
        .cornerRadius(16)
        .padding(.horizontal, 16)
        .padding(.top, 40)
    }

    // MARK: - Search Bar
    private var searchBarView: some View {
        HStack {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.gray)

            TextField("Search country, city, address, or coords...", text: $searchQuery, onEditingChanged: { focused in
                isSearchFocused = focused
            }, onCommit: {
                searchService.search(query: searchQuery, region: region)
            })
            .foregroundColor(.white)
            .font(.system(size: 13))
            .onChange(of: searchQuery) { val in
                if !val.isEmpty {
                    searchService.search(query: val, region: region)
                }
            }

            if !searchQuery.isEmpty {
                Button(action: {
                    searchQuery = ""
                    searchService.searchResults = []
                }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.gray)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(red: 0.13, green: 0.13, blue: 0.15))
        .cornerRadius(12)
        .padding(.horizontal, 16)
    }

    // MARK: - Search Results Dropdown
    private var searchResultsDropdown: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    if !searchService.searchResults.isEmpty {
                        ForEach(searchService.searchResults) { item in
                            Button(action: {
                                teleportTo(coord: item.coordinate, name: item.title)
                                storage.addSearchHistory(query: searchQuery, title: item.title, subtitle: item.subtitle, coordinate: item.coordinate)
                                isSearchFocused = false
                                searchQuery = ""
                            }) {
                                HStack {
                                    Image(systemName: "mappin.and.ellipse")
                                        .foregroundColor(.red)
                                    VStack(alignment: .leading) {
                                        Text(item.title)
                                            .font(.system(size: 13, weight: .bold))
                                            .foregroundColor(.white)
                                        Text(item.subtitle)
                                            .font(.system(size: 11))
                                            .foregroundColor(.gray)
                                            .lineLimit(1)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                            }
                            Divider().background(Color.white.opacity(0.08))
                        }
                    }
                }
            }
            .frame(maxHeight: 200)
        }
        .background(Color(red: 0.12, green: 0.12, blue: 0.14))
        .cornerRadius(14)
        .padding(.horizontal, 16)
        .shadow(radius: 8)
    }

    // MARK: - Segmented Mode Picker
    private var segmentedModePicker: some View {
        HStack(spacing: 4) {
            modeButton(title: "Fixed", id: "FIXED")
            modeButton(title: "Route", id: "ROUTE")
            modeButton(title: "Joystick", id: "JOYSTICK")
            modeButton(title: "GPX", id: "GPX")
        }
        .padding(4)
        .background(Color.black.opacity(0.65))
        .cornerRadius(14)
        .padding(.horizontal, 16)
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

    // MARK: - Bottom Control Card
    private var bottomControlCard: some View {
        VStack(spacing: 12) {
            // Quick Destination 1-Tap Slots
            quickDestinationsBar

            if selectedTab == "FIXED" {
                fixedControlsView
            } else if selectedTab == "ROUTE" {
                routeControlsView
            } else if selectedTab == "JOYSTICK" {
                joystickControlsView
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

    // MARK: - Quick Destinations Bar
    private var quickDestinationsBar: some View {
        HStack(spacing: 8) {
            quickSlotButton(name: storage.slot1Name, lat: storage.slot1Lat, lon: storage.slot1Lon)
            quickSlotButton(name: storage.slot2Name, lat: storage.slot2Lat, lon: storage.slot2Lon)
            quickSlotButton(name: storage.slot3Name, lat: storage.slot3Lat, lon: storage.slot3Lon)
        }
    }

    private func quickSlotButton(name: String, lat: Double, lon: Double) -> some View {
        Button(action: {
            teleportTo(coord: CLLocationCoordinate2D(latitude: lat, longitude: lon), name: name)
        }) {
            Text(name)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(Color.white.opacity(0.08))
                .cornerRadius(8)
        }
    }

    // MARK: - Mode Controls Subviews

    private var fixedControlsView: some View {
        VStack(spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("COORDINATES")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(.gray)
                    Text(String(format: "%.5f°, %.5f°", pinnedLocation.latitude, pinnedLocation.longitude))
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }
                Spacer()

                Button(action: {
                    storage.addFavorite(name: activeLocationName, coordinate: pinnedLocation)
                }) {
                    Image(systemName: "star")
                        .foregroundColor(.red)
                        .padding(8)
                        .background(Color.white.opacity(0.08))
                        .clipShape(Circle())
                }
            }

            HStack(spacing: 10) {
                Button(action: {
                    engine.startFixed(coordinate: pinnedLocation)
                    updateLocationName(coord: pinnedLocation)
                }) {
                    HStack {
                        Image(systemName: "location.fill")
                        Text(engine.isSimulating ? "Teleported Active" : "Inject Location")
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
        VStack(spacing: 10) {
            HStack {
                Text("\(waypoints.count) Waypoints")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white)

                Spacer()

                Text(storage.formatSpeed(speedKmh))
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.red)
            }

            Slider(value: $speedKmh, in: 5...160, step: 5)
                .accentColor(.red)

            HStack(spacing: 8) {
                Button(action: {
                    waypoints.append(RoutePoint(latitude: region.center.latitude, longitude: region.center.longitude))
                }) {
                    Text("+ Add Waypoint")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.1))
                        .cornerRadius(8)
                }

                Button(action: {
                    if !waypoints.isEmpty { waypoints.removeLast() }
                }) {
                    Text("Undo")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.gray)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.08))
                        .cornerRadius(8)
                }

                Spacer()

                Button(action: { showSaveRouteDialog = true }) {
                    Text("Save Route")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.red)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.08))
                        .cornerRadius(8)
                }
            }

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

    private var joystickControlsView: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Floating Joystick Mode")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
                Text(storage.formatSpeed(speedKmh))
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.red)
            }

            Slider(value: $speedKmh, in: 2...80, step: 2)
                .accentColor(.red)

            Text("Drag the 360° red joystick on the map to walk or drive freely.")
                .font(.system(size: 11))
                .foregroundColor(.gray)
        }
    }

    private var gpxExportView: some View {
        VStack(spacing: 12) {
            Text("Export your current route as an Apple GPX file to simulate location via Xcode, LocationSimulator, or SideStore.")
                .font(.system(size: 12))
                .foregroundColor(.gray)

            Button(action: {
                let pts = waypoints.isEmpty ? [RoutePoint(latitude: pinnedLocation.latitude, longitude: pinnedLocation.longitude)] : waypoints
                if let url = GPXManager.shared.exportGPXFile(waypoints: pts) {
                    gpxExportURL = url
                    showShareSheet = true
                }
            }) {
                HStack {
                    Image(systemName: "square.and.arrow.up")
                    Text("AirDrop / Export GPX Track")
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

    // MARK: - Update Available Banner
    private var updateAvailableBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle.fill")
                .foregroundColor(.red)
                .font(.system(size: 18, weight: .bold))

            VStack(alignment: .leading, spacing: 2) {
                Text("Update Available — v\(updateChecker.latestVersion)")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white)
                Text("Tap to download the latest Nowhere build")
                    .font(.system(size: 10))
                    .foregroundColor(.gray)
            }

            Spacer()

            Button(action: {
                if let url = updateChecker.releaseURL {
                    UIApplication.shared.open(url)
                }
            }) {
                Text("Update")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.red)
                    .cornerRadius(8)
            }

            Button(action: {
                withAnimation(.easeInOut(duration: 0.25)) {
                    updateBannerDismissed = true
                }
            }) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.gray)
                    .padding(6)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(
            Color(red: 0.12, green: 0.05, blue: 0.05)
                .overlay(Color.red.opacity(0.15))
        )
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.red.opacity(0.5), lineWidth: 1)
        )
        .padding(.horizontal, 16)
    }

    // MARK: - Helpers

    private func teleportTo(coord: CLLocationCoordinate2D, name: String) {
        pinnedLocation = coord
        activeLocationName = name
        region.center = coord
        engine.startFixed(coordinate: coord)
    }

    private func updateLocationName(coord: CLLocationCoordinate2D) {
        SearchService.shared.reverseGeocode(coordinate: coord) { placeName in
            self.activeLocationName = placeName
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
