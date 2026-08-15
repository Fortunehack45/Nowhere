import Foundation
import CoreLocation

enum SimulationMode: Equatable {
    case idle
    case fixed(coordinate: CLLocationCoordinate2D, altitude: Double)
    case route(waypoints: [RoutePoint], speedKmh: Double, isLooping: Bool = true, mode: TransportMode)
    case joystick(coordinate: CLLocationCoordinate2D, speedKmh: Double)
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
