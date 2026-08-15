import Foundation
import CoreLocation

class GPXManager {

    static let shared = GPXManager()

    /**
     * Generates a standard XML GPX file from a list of waypoints.
     */
    func generateGPX(waypoints: [RoutePoint], title: String = "Nowhere Simulated Route") -> String {
        let dateFormatter = ISO8601DateFormatter()
        let nowString = dateFormatter.string(from: Date())

        var gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Nowhere Precision GPS Simulator for iOS"
             xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <metadata>
                <name>\(title)</name>
                <time>\(nowString)</time>
            </metadata>
            <trk>
                <name>\(title)</name>
                <trkseg>
        """

        for (index, pt) in waypoints.enumerated() {
            let pointTime = dateFormatter.string(from: Date().addingTimeInterval(Double(index * 2)))
            gpx += """
                    <trkpt lat="\(pt.latitude)" lon="\(pt.longitude)">
                        <ele>\(pt.altitude)</ele>
                        <time>\(pointTime)</time>
                    </trkpt>
            """
        }

        gpx += """
                </trkseg>
            </trk>
        </gpx>
        """

        return gpx
    }

    /**
     * Writes GPX string to a temporary file URL for sharing or AirDrop.
     */
    func exportGPXFile(waypoints: [RoutePoint], filename: String = "Nowhere_Route.gpx") -> URL? {
        let gpxContent = generateGPX(waypoints: waypoints)
        let tempDir = FileManager.default.temporaryDirectory
        let fileURL = tempDir.appendingPathComponent(filename)

        do {
            try gpxContent.write(to: fileURL, atomically: true, encoding: .utf8)
            return fileURL
        } catch {
            print("Failed writing GPX file: \(error.localizedDescription)")
            return nil
        }
    }
}
