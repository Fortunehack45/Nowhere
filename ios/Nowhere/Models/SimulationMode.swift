import Foundation
import CoreLocation

extension CLLocationCoordinate2D: Equatable {
    public static func == (lhs: CLLocationCoordinate2D, rhs: CLLocationCoordinate2D) -> Bool {
        abs(lhs.latitude - rhs.latitude) < 0.0000001 && abs(lhs.longitude - rhs.longitude) < 0.0000001
    }
}

enum SimulationMode: Equatable {
    case idle
    case fixed(coordinate: CLLocationCoordinate2D, altitude: Double)
    case route(waypoints: [RoutePoint], speedKmh: Double, isLooping: Bool = true, mode: TransportMode)
    case joystick(coordinate: CLLocationCoordinate2D, speedKmh: Double)

    public static func == (lhs: SimulationMode, rhs: SimulationMode) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle):
            return true
        case (.fixed(let c1, let a1), .fixed(let c2, let a2)):
            return c1 == c2 && a1 == a2
        case (.route(let w1, let s1, let l1, let m1), .route(let w2, let s2, let l2, let m2)):
            return w1 == w2 && s1 == s2 && l1 == l2 && m1 == m2
        case (.joystick(let c1, let s1), .joystick(let c2, let s2)):
            return c1 == c2 && s1 == s2
        default:
            return false
        }
    }
}

enum TransportMode: String, CaseIterable, Identifiable, Codable {
    case walk = "WALK"
    case bicycle = "BICYCLE"
    case vehicle = "VEHICLE"
    case highSpeed = "HIGH_SPEED"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .walk: return "Walk (5 km/h)"
        case .bicycle: return "Bicycle (15 km/h)"
        case .vehicle: return "Drive (60 km/h)"
        case .highSpeed: return "Express (120 km/h)"
        }
    }

    var defaultSpeedKmh: Double {
        switch self {
        case .walk: return 5.0
        case .bicycle: return 15.0
        case .vehicle: return 60.0
        case .highSpeed: return 120.0
        }
    }
}

struct RoutePoint: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var latitude: Double
    var longitude: Double
    var altitude: Double = 15.0

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct FavoriteLocation: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var name: String
    var latitude: Double
    var longitude: Double
    var tag: String = "General"
    var createdAt: Date = Date()

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct SavedRouteItem: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var name: String
    var waypoints: [RoutePoint]
    var totalDistanceMeters: Double
    var defaultSpeedKmh: Double
    var createdAt: Date = Date()
}

struct SearchHistoryItem: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var query: String
    var title: String
    var subtitle: String
    var latitude: Double
    var longitude: Double
    var timestamp: Date = Date()
}
