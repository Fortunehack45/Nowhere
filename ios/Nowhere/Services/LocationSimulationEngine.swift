import Foundation
import CoreLocation
import Combine

class LocationSimulationEngine: ObservableObject {

    static let shared = LocationSimulationEngine()

    @Published var currentCoordinate: CLLocationCoordinate2D = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    @Published var currentSpeedKmh: Double = 0.0
    @Published var currentBearing: Double = 0.0
    @Published var isSimulating: Bool = false
    @Published var isPaused: Bool = false
    @Published var activeMode: SimulationMode = .idle
    @Published var routeProgress: Double = 0.0 // 0.0 to 1.0

    private var simulationTimer: Timer?
    private var waypoints: [RoutePoint] = []
    private var currentLegIndex: Int = 0
    private var legProgress: Double = 0.0
    private var isLooping: Bool = true
    private var transportMode: TransportMode = .vehicle

    func startFixed(coordinate: CLLocationCoordinate2D, altitude: Double = 15.0) {
        stop()
        currentCoordinate = coordinate
        currentSpeedKmh = 0.0
        currentBearing = 0.0
        activeMode = .fixed(coordinate: coordinate, altitude: altitude)
        isSimulating = true
        isPaused = false

        if StorageManager.shared.isAutoVpnSyncEnabled {
            IpNodeManager.shared.autoSyncWithLocation(coordinate: coordinate)
        }
        WeatherService.shared.fetchWeather(for: coordinate)
    }

    func startRoute(waypoints: [RoutePoint], speedKmh: Double, isLooping: Bool = true, mode: TransportMode = .vehicle) {
        guard waypoints.count >= 2 else { return }
        stop()
        self.waypoints = waypoints
        self.currentLegIndex = 0
        self.legProgress = 0.0
        self.isLooping = isLooping
        self.transportMode = mode
        self.currentSpeedKmh = speedKmh
        self.activeMode = .route(waypoints: waypoints, speedKmh: speedKmh, isLooping: isLooping, mode: mode)
        self.isSimulating = true
        self.isPaused = false

        if let first = waypoints.first {
            currentCoordinate = first.coordinate
            if StorageManager.shared.isAutoVpnSyncEnabled {
                IpNodeManager.shared.autoSyncWithLocation(coordinate: first.coordinate)
            }
            WeatherService.shared.fetchWeather(for: first.coordinate)
        }

        startTimer()
    }

    func togglePause() {
        isPaused.toggle()
    }

    func stop() {
        simulationTimer?.invalidate()
        simulationTimer = nil
        isSimulating = false
        isPaused = false
        activeMode = .idle
        currentSpeedKmh = 0.0
        routeProgress = 0.0

        if StorageManager.shared.isAutoVpnSyncEnabled {
            IpNodeManager.shared.disconnect()
        }
    }

    private func startTimer() {
        simulationTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.tickSimulation(deltaSeconds: 0.5)
        }
    }

    private func tickSimulation(deltaSeconds: Double) {
        guard isSimulating, !isPaused, waypoints.count >= 2 else { return }

        let startPt = waypoints[currentLegIndex]
        let nextIndex = (currentLegIndex + 1) % waypoints.count
        let endPt = waypoints[nextIndex]

        let legDistanceMeters = calculateDistanceMeters(from: startPt.coordinate, to: endPt.coordinate)
        guard legDistanceMeters > 0.1 else {
            advanceLeg()
            return
        }

        let speedMps = (currentSpeedKmh * 1000.0) / 3600.0
        let distanceCovered = speedMps * deltaSeconds
        legProgress += distanceCovered / legDistanceMeters

        if legProgress >= 1.0 {
            advanceLeg()
        } else {
            currentCoordinate = interpolateCoordinate(from: startPt.coordinate, to: endPt.coordinate, fraction: legProgress)
            currentBearing = calculateBearing(from: startPt.coordinate, to: endPt.coordinate)
            routeProgress = (Double(currentLegIndex) + legProgress) / Double(waypoints.count - 1)
        }
    }

    private func advanceLeg() {
        legProgress = 0.0
        currentLegIndex += 1
        if currentLegIndex >= waypoints.count - 1 {
            if isLooping {
                currentLegIndex = 0
            } else {
                stop()
            }
        }
    }

    // MARK: - Geodesic Math

    func calculateDistanceMeters(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let locA = CLLocation(latitude: from.latitude, longitude: from.longitude)
        let locB = CLLocation(latitude: to.latitude, longitude: to.longitude)
        return locA.distance(from: locB)
    }

    func calculateBearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180
        let lon1 = from.longitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let lon2 = to.longitude * .pi / 180

        let dLon = lon2 - lon1
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let radiansBearing = atan2(y, x)
        return (radiansBearing * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    func interpolateCoordinate(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, fraction: Double) -> CLLocationCoordinate2D {
        let lat = from.latitude + (to.latitude - from.latitude) * fraction
        let lon = from.longitude + (to.longitude - from.longitude) * fraction
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}
