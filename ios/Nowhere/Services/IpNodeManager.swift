import Foundation
import CoreLocation
import Combine

struct IpNode: Identifiable, Codable, Equatable {
    let id: String
    let countryCode: String
    let countryName: String
    let cityName: String
    let flagEmoji: String
    let latitude: Double
    let longitude: Double
    let serverAddress: String
    let pingLatencyMs: Int
}

class IpNodeManager: ObservableObject {

    static let shared = IpNodeManager()

    enum ConnectionState: Equatable {
        case disconnected
        case connecting(node: IpNode)
        case connected(node: IpNode)
    }

    @Published var connectionState: ConnectionState = .disconnected
    @Published var isAutoSyncEnabled: Bool = true
    @Published var availableNodes: [IpNode] = []

    init() {
        populateNodes()
    }

    private func populateNodes() {
        availableNodes = [
            IpNode(id: "us-west-sf", countryCode: "US", countryName: "United States", cityName: "San Francisco, CA", flagEmoji: "🇺🇸", latitude: 37.7749, longitude: -122.4194, serverAddress: "us-west.nowhere.priv", pingLatencyMs: 14),
            IpNode(id: "us-east-ny", countryCode: "US", countryName: "United States", cityName: "New York, NY", flagEmoji: "🇺🇸", latitude: 40.7128, longitude: -74.0060, serverAddress: "us-east.nowhere.priv", pingLatencyMs: 22),
            IpNode(id: "uk-lon", countryCode: "GB", countryName: "United Kingdom", cityName: "London", flagEmoji: "🇬🇧", latitude: 51.5074, longitude: -0.1278, serverAddress: "uk-lon.nowhere.priv", pingLatencyMs: 28),
            IpNode(id: "de-fra", countryCode: "DE", countryName: "Germany", cityName: "Frankfurt", flagEmoji: "🇩🇪", latitude: 50.1109, longitude: 8.6821, serverAddress: "de-fra.nowhere.priv", pingLatencyMs: 34),
            IpNode(id: "fr-par", countryCode: "FR", countryName: "France", cityName: "Paris", flagEmoji: "🇫🇷", latitude: 48.8566, longitude: 2.3522, serverAddress: "fr-par.nowhere.priv", pingLatencyMs: 31),
            IpNode(id: "jp-tyo", countryCode: "JP", countryName: "Japan", cityName: "Tokyo", flagEmoji: "🇯🇵", latitude: 35.6762, longitude: 139.6503, serverAddress: "jp-tyo.nowhere.priv", pingLatencyMs: 48),
            IpNode(id: "sg-sin", countryCode: "SG", countryName: "Singapore", cityName: "Singapore", flagEmoji: "🇸🇬", latitude: 1.3521, longitude: 103.8198, serverAddress: "sg-sin.nowhere.priv", pingLatencyMs: 42),
            IpNode(id: "au-syd", countryCode: "AU", countryName: "Australia", cityName: "Sydney", flagEmoji: "🇦🇺", latitude: -33.8688, longitude: 151.2093, serverAddress: "au-syd.nowhere.priv", pingLatencyMs: 65),
            IpNode(id: "ca-tor", countryCode: "CA", countryName: "Canada", cityName: "Toronto", flagEmoji: "🇨🇦", latitude: 43.6532, longitude: -79.3832, serverAddress: "ca-tor.nowhere.priv", pingLatencyMs: 25),
            IpNode(id: "nl-ams", countryCode: "NL", countryName: "Netherlands", cityName: "Amsterdam", flagEmoji: "🇳🇱", latitude: 52.3676, longitude: 4.9041, serverAddress: "nl-ams.nowhere.priv", pingLatencyMs: 29),
            IpNode(id: "ch-zur", countryCode: "CH", countryName: "Switzerland", cityName: "Zurich", flagEmoji: "🇨🇭", latitude: 47.3769, longitude: 8.5417, serverAddress: "ch-zur.nowhere.priv", pingLatencyMs: 33)
        ]
    }

    func findClosestNode(to coordinate: CLLocationCoordinate2D) -> IpNode {
        var closestNode = availableNodes[0]
        var minDistance: Double = .greatestFiniteMagnitude

        let target = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        for node in availableNodes {
            let nodeLoc = CLLocation(latitude: node.latitude, longitude: node.longitude)
            let dist = target.distance(from: nodeLoc)
            if dist < minDistance {
                minDistance = dist
                closestNode = node
            }
        }
        return closestNode
    }

    func connect(to node: IpNode) {
        connectionState = .connecting(node: node)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            self?.connectionState = .connected(node: node)
        }
    }

    func disconnect() {
        connectionState = .disconnected
    }

    func autoSyncWithLocation(coordinate: CLLocationCoordinate2D) {
        guard isAutoSyncEnabled else { return }
        let closest = findClosestNode(to: coordinate)
        connect(to: closest)
    }
}
