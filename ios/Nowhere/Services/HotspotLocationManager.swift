import Foundation
import CoreLocation
import Combine

class HotspotLocationManager: ObservableObject {

    static let shared = HotspotLocationManager()

    @Published var isServerRunning: Bool = false
    @Published var connectedClientsCount: Int = 0
    @Published var serverPort: Int = 8080
    @Published var localIpAddress: String = "192.168.1.100"

    func startBroadcast() {
        isServerRunning = true
        connectedClientsCount = 1
    }

    func stopBroadcast() {
        isServerRunning = false
        connectedClientsCount = 0
    }
}
