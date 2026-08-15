import SwiftUI

@main
struct NowhereApp: App {
    var body: some Scene {
        WindowGroup {
            MainMapView()
                .preferredColorScheme(.dark)
        }
    }
}
