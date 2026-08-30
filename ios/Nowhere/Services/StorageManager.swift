import Foundation
import CoreLocation
import SwiftUI

class StorageManager: ObservableObject {

    static let shared = StorageManager()

    // MARK: - Published State
    @Published var favorites: [FavoriteLocation] = []
    @Published var savedRoutes: [SavedRouteItem] = []
    @Published var searchHistory: [SearchHistoryItem] = []

    // Quick Destinations Widget Slots
    @AppStorage("widget_slot_1_name") var slot1Name: String = "Paris"
    @AppStorage("widget_slot_1_lat") var slot1Lat: Double = 48.8566
    @AppStorage("widget_slot_1_lon") var slot1Lon: Double = 2.3522

    @AppStorage("widget_slot_2_name") var slot2Name: String = "Tokyo"
    @AppStorage("widget_slot_2_lat") var slot2Lat: Double = 35.6762
    @AppStorage("widget_slot_2_lon") var slot2Lon: Double = 139.6503

    @AppStorage("widget_slot_3_name") var slot3Name: String = "New York"
    @AppStorage("widget_slot_3_lat") var slot3Lat: Double = 40.7128
    @AppStorage("widget_slot_3_lon") var slot3Lon: Double = -74.0060

    // Settings
    @AppStorage("app_theme") var appTheme: String = "DARK"
    @AppStorage("distance_unit") var distanceUnit: String = "METRIC"
    @AppStorage("randomize_jitter") var randomizeJitter: Bool = false
    @AppStorage("jitter_radius_meters") var jitterRadiusMeters: Double = 2.0
    @AppStorage("truncate_decimals") var truncateDecimals: Int = -1 // -1 = full precision
    @AppStorage("default_altitude") var defaultAltitude: Double = 15.0
    @AppStorage("haptic_feedback") var hapticFeedback: Bool = true

    // Anti-Detection Ghost Cloak & Auto-VPN
    @AppStorage("is_ghost_cloak_enabled") var isGhostCloakEnabled: Bool = true
    @AppStorage("is_nmea_synthesis_enabled") var isNmeaSynthesisEnabled: Bool = true
    @AppStorage("is_clock_drift_emulation_enabled") var isClockDriftEmulationEnabled: Bool = true
    @AppStorage("is_sensor_kinematics_enabled") var isSensorKinematicsEnabled: Bool = true
    @AppStorage("is_auto_vpn_sync_enabled") var isAutoVpnSyncEnabled: Bool = true

    private let favoritesKey = "nowhere_favorites_v1"
    private let routesKey = "nowhere_saved_routes_v1"
    private let historyKey = "nowhere_search_history_v1"

    init() {
        loadAll()
    }

    func loadAll() {
        loadFavorites()
        loadSavedRoutes()
        loadSearchHistory()
    }

    // MARK: - Favorites
    func addFavorite(name: String, coordinate: CLLocationCoordinate2D, tag: String = "General") {
        let fav = FavoriteLocation(name: name, latitude: coordinate.latitude, longitude: coordinate.longitude, tag: tag)
        favorites.append(fav)
        saveFavorites()
    }

    func deleteFavorite(id: String) {
        favorites.removeAll { $0.id == id }
        saveFavorites()
    }

    private func saveFavorites() {
        if let data = try? JSONEncoder().encode(favorites) {
            UserDefaults.standard.set(data, forKey: favoritesKey)
        }
    }

    private func loadFavorites() {
        if let data = UserDefaults.standard.data(forKey: favoritesKey),
           let decoded = try? JSONDecoder().decode([FavoriteLocation].self, from: data) {
            favorites = decoded
        } else {
            // Default sample favorites
            favorites = [
                FavoriteLocation(name: "Eiffel Tower, Paris", latitude: 48.8584, longitude: 2.2945, tag: "Vacation"),
                FavoriteLocation(name: "Shibuya Crossing, Tokyo", latitude: 35.6595, longitude: 139.7004, tag: "City"),
                FavoriteLocation(name: "Times Square, New York", latitude: 40.7580, longitude: -73.9855, tag: "Sightseeing")
            ]
            saveFavorites()
        }
    }

    // MARK: - Saved Routes
    func saveRoute(name: String, waypoints: [RoutePoint], speed: Double) {
        var totalDist: Double = 0.0
        for i in 0..<(waypoints.count - 1) {
            let locA = CLLocation(latitude: waypoints[i].latitude, longitude: waypoints[i].longitude)
            let locB = CLLocation(latitude: waypoints[i+1].latitude, longitude: waypoints[i+1].longitude)
            totalDist += locA.distance(from: locB)
        }
        let route = SavedRouteItem(name: name, waypoints: waypoints, totalDistanceMeters: totalDist, defaultSpeedKmh: speed)
        savedRoutes.append(route)
        if let data = try? JSONEncoder().encode(savedRoutes) {
            UserDefaults.standard.set(data, forKey: routesKey)
        }
    }

    func deleteRoute(id: String) {
        savedRoutes.removeAll { $0.id == id }
        if let data = try? JSONEncoder().encode(savedRoutes) {
            UserDefaults.standard.set(data, forKey: routesKey)
        }
    }

    private func loadSavedRoutes() {
        if let data = UserDefaults.standard.data(forKey: routesKey),
           let decoded = try? JSONDecoder().decode([SavedRouteItem].self, from: data) {
            savedRoutes = decoded
        }
    }

    // MARK: - Search History
    func addSearchHistory(query: String, title: String, subtitle: String, coordinate: CLLocationCoordinate2D) {
        searchHistory.removeAll { $0.title == title }
        let item = SearchHistoryItem(query: query, title: title, subtitle: subtitle, latitude: coordinate.latitude, longitude: coordinate.longitude)
        searchHistory.insert(item, at: 0)
        if searchHistory.count > 20 { searchHistory.removeLast() }
        if let data = try? JSONEncoder().encode(searchHistory) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
    }

    func clearSearchHistory() {
        searchHistory.removeAll()
        UserDefaults.standard.removeObject(forKey: historyKey)
    }

    private func loadSearchHistory() {
        if let data = UserDefaults.standard.data(forKey: historyKey),
           let decoded = try? JSONDecoder().decode([SearchHistoryItem].self, from: data) {
            searchHistory = decoded
        }
    }

    // MARK: - Unit Formatting
    func formatSpeed(_ kmh: Double) -> String {
        if distanceUnit == "IMPERIAL" {
            let mph = kmh * 0.621371
            return String(format: "%.1f MPH", mph)
        } else {
            return String(format: "%.1f KM/H", kmh)
        }
    }

    func formatDistance(_ meters: Double) -> String {
        if distanceUnit == "IMPERIAL" {
            let feet = meters * 3.28084
            if feet >= 5280 {
                return String(format: "%.2f mi", feet / 5280)
            } else {
                return String(format: "%.0f ft", feet)
            }
        } else {
            if meters >= 1000 {
                return String(format: "%.2f km", meters / 1000)
            } else {
                return String(format: "%.0f m", meters)
            }
        }
    }
}
