import SwiftUI

@main
struct NowhereApp: App {

    @StateObject private var updateChecker = AppUpdateChecker.shared

    var body: some Scene {
        WindowGroup {
            MainMapView()
                .preferredColorScheme(.dark)
                .environmentObject(updateChecker)
                .onAppear {
                    // Check for updates shortly after launch (5s delay to avoid blocking startup)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                        updateChecker.checkForUpdate()
                    }
                }
        }
    }
}
