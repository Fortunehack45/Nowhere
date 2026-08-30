import Foundation
import SwiftUI
import Combine

class SessionTimerManager: ObservableObject {

    static let shared = SessionTimerManager()

    static let defaultSessionDurationSeconds: TimeInterval = 7200 // 2 hours
    static let rewardExtensionDurationSeconds: TimeInterval = 7200 // +2 hours

    @Published var remainingSeconds: TimeInterval = 7200
    @Published var isSessionActive: Bool = false
    @Published var isExpired: Bool = false

    private var countdownTimer: Timer?
    private let expiresKey = "nowhere_session_expires_timestamp"

    init() {
        restoreSession()
    }

    func startSession(duration: TimeInterval = defaultSessionDurationSeconds) {
        let expireDate = Date().addingTimeInterval(duration)
        UserDefaults.standard.set(expireDate.timeIntervalSince1970, forKey: expiresKey)
        remainingSeconds = duration
        isSessionActive = true
        isExpired = false
        startTimer()
    }

    func extendSession(additionalSeconds: TimeInterval = rewardExtensionDurationSeconds) {
        let currentRemaining = max(0, remainingSeconds)
        let newRemaining = currentRemaining + additionalSeconds
        let newExpireDate = Date().addingTimeInterval(newRemaining)
        UserDefaults.standard.set(newExpireDate.timeIntervalSince1970, forKey: expiresKey)
        remainingSeconds = newRemaining
        isSessionActive = true
        isExpired = false
        startTimer()
    }

    func restoreSession() {
        let expireTs = UserDefaults.standard.double(forKey: expiresKey)
        guard expireTs > 0 else {
            startSession()
            return
        }

        let nowTs = Date().timeIntervalSince1970
        let diff = expireTs - nowTs
        if diff > 0 {
            remainingSeconds = diff
            isSessionActive = true
            isExpired = false
            startTimer()
        } else {
            remainingSeconds = 0
            isSessionActive = false
            isExpired = true
        }
    }

    private func startTimer() {
        countdownTimer?.invalidate()
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if self.remainingSeconds > 0 {
                self.remainingSeconds -= 1
            } else {
                self.handleExpiration()
            }
        }
    }

    private func handleExpiration() {
        countdownTimer?.invalidate()
        countdownTimer = nil
        isSessionActive = false
        isExpired = true
        LocationSimulationEngine.shared.stop()
        IpNodeManager.shared.disconnect()
    }

    var formattedTimeRemaining: String {
        let total = max(0, Int(remainingSeconds))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
