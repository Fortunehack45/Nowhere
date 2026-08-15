import SwiftUI
import CoreLocation

struct JoystickOverlayView: View {

    @Binding var currentCoordinate: CLLocationCoordinate2D
    var speedKmh: Double
    var isEnabled: Bool
    var onCoordinateUpdated: (CLLocationCoordinate2D) -> Void

    @State private var dragOffset: CGSize = .zero
    @State private var timer: Timer? = nil

    private let baseRadius: CGFloat = 60
    private let knobRadius: CGFloat = 24

    var body: some View {
        ZStack {
            // Joystick Base Circle
            Circle()
                .fill(Color(red: 0.12, green: 0.12, blue: 0.14).opacity(0.85))
                .frame(width: baseRadius * 2, height: baseRadius * 2)
                .overlay(
                    Circle()
                        .stroke(Color.red.opacity(0.4), lineWidth: 2)
                )
                .shadow(color: .black.opacity(0.4), radius: 8)

            // Center Knob
            Circle()
                .fill(
                    LinearGradient(
                        colors: [Color.red, Color(red: 0.8, green: 0.1, blue: 0.1)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: knobRadius * 2, height: knobRadius * 2)
                .offset(dragOffset)
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            let translation = value.translation
                            let distance = sqrt(translation.width * translation.width + translation.height * translation.height)
                            let maxDistance = baseRadius - knobRadius

                            if distance <= maxDistance {
                                dragOffset = translation
                            } else {
                                let angle = atan2(translation.height, translation.width)
                                dragOffset = CGSize(
                                    width: cos(angle) * maxDistance,
                                    height: sin(angle) * maxDistance
                                )
                            }

                            startMoving()
                        }
                        .onEnded { _ in
                            dragOffset = .zero
                            stopMoving()
                        }
                )
        }
    }

    private func startMoving() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
            guard dragOffset != .zero else { return }

            let normX = Double(dragOffset.width / baseRadius)
            let normY = Double(dragOffset.height / baseRadius)

            // Speed step
            let stepMps = (speedKmh * 1000.0) / 3600.0 * 0.2
            let dLat = -(normY * stepMps) / 111139.0
            let dLon = (normX * stepMps) / (111139.0 * cos(currentCoordinate.latitude * .pi / 180.0))

            let newLat = currentCoordinate.latitude + dLat
            let newLon = currentCoordinate.longitude + dLon
            let newCoord = CLLocationCoordinate2D(latitude: newLat, longitude: newLon)

            currentCoordinate = newCoord
            onCoordinateUpdated(newCoord)
        }
    }

    private func stopMoving() {
        timer?.invalidate()
        timer = nil
    }
}
