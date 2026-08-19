import Foundation
import Combine

// MARK: - AppUpdateChecker
/// Checks the GitHub Releases API for the latest version of Nowhere and
/// compares it against the currently installed bundle version.
/// Publishes `updateAvailable`, `latestVersion`, and `releaseURL` for UI binding.
class AppUpdateChecker: ObservableObject {

    static let shared = AppUpdateChecker()

    // MARK: - Published State
    @Published var updateAvailable: Bool = false
    @Published var latestVersion: String = ""
    @Published var releaseURL: URL? = nil
    @Published var isChecking: Bool = false
    @Published var lastCheckedAt: Date? = nil

    // MARK: - Config
    private let githubReleasesURL = URL(string: "https://api.github.com/repos/Fortunehack45/Nowhere/releases/latest")!
    private let githubReleasesPageURL = URL(string: "https://github.com/Fortunehack45/Nowhere/releases/latest")!

    // Current installed version from app bundle (e.g. "1.0.57")
    var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    private init() {}

    // MARK: - Public API

    /// Fetches the latest GitHub release and updates published properties.
    /// Safe to call multiple times; no-ops while already checking.
    func checkForUpdate() {
        guard !isChecking else { return }
        isChecking = true

        var request = URLRequest(url: githubReleasesURL)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 10

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                self?.isChecking = false
                self?.lastCheckedAt = Date()
            }

            guard let self = self,
                  let data = data,
                  error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tagName = json["tag_name"] as? String else {
                return
            }

            // Strip leading 'v' prefix if present (e.g. "v1.0.57" → "1.0.57")
            let remoteVersion = tagName.hasPrefix("v") ? String(tagName.dropFirst()) : tagName

            // Resolve APK download URL from release assets if available
            var apkDownloadURL: URL? = nil
            if let assets = json["assets"] as? [[String: Any]] {
                for asset in assets {
                    if let name = asset["name"] as? String,
                       name.hasSuffix(".apk"),
                       let downloadURLString = asset["browser_download_url"] as? String {
                        apkDownloadURL = URL(string: downloadURLString)
                        break
                    }
                }
            }

            let shouldUpdate = self.isVersionNewer(remote: remoteVersion, current: self.currentVersion)

            DispatchQueue.main.async {
                self.latestVersion = remoteVersion
                self.updateAvailable = shouldUpdate
                self.releaseURL = apkDownloadURL ?? self.githubReleasesPageURL
            }
        }.resume()
    }

    // MARK: - Version Comparison

    /// Returns true if `remote` version is strictly newer than `current` using semver comparison.
    private func isVersionNewer(remote: String, current: String) -> Bool {
        let remoteParts = remote.split(separator: ".").compactMap { Int($0) }
        let currentParts = current.split(separator: ".").compactMap { Int($0) }

        let maxLen = max(remoteParts.count, currentParts.count)
        for i in 0..<maxLen {
            let r = i < remoteParts.count ? remoteParts[i] : 0
            let c = i < currentParts.count ? currentParts[i] : 0
            if r > c { return true }
            if r < c { return false }
        }
        return false // equal
    }
}
