import SwiftUI
import CoreLocation

struct SavedRoutesSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var storage = StorageManager.shared
    var onLoadRoute: ([RoutePoint], Double, String) -> Void

    @State private var gpxExportURL: URL? = nil
    @State private var showShareSheet: Bool = false

    var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.08, green: 0.08, blue: 0.10).ignoresSafeArea()

                if storage.savedRoutes.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "map")
                            .font(.system(size: 48))
                            .foregroundColor(.gray)
                        Text("No Saved Routes")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Plot waypoints on the map and tap 'Save Route' to store here.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                } else {
                    List {
                        ForEach(storage.savedRoutes) { route in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(route.name)
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundColor(.white)
                                    Spacer()
                                    Text("\(route.waypoints.count) pts")
                                        .font(.system(size: 11, weight: .bold))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Color.red.opacity(0.2))
                                        .foregroundColor(.red)
                                        .cornerRadius(6)
                                }

                                Text("Distance: \(storage.formatDistance(route.totalDistanceMeters)) • Speed: \(storage.formatSpeed(route.defaultSpeedKmh))")
                                    .font(.system(size: 12))
                                    .foregroundColor(.gray)

                                HStack(spacing: 12) {
                                    Button(action: {
                                        onLoadRoute(route.waypoints, route.defaultSpeedKmh, route.name)
                                        presentationMode.wrappedValue.dismiss()
                                    }) {
                                        HStack {
                                            Image(systemName: "play.fill")
                                            Text("Start Route")
                                        }
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.white)
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 6)
                                        .background(Color.red)
                                        .cornerRadius(8)
                                    }

                                    Button(action: {
                                        if let url = GPXManager.shared.exportGPXFile(waypoints: route.waypoints, filename: "\(route.name.replacingOccurrences(of: " ", with: "_")).gpx") {
                                            gpxExportURL = url
                                            showShareSheet = true
                                        }
                                    }) {
                                        HStack {
                                            Image(systemName: "square.and.arrow.up")
                                            Text("Export GPX")
                                        }
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.gray)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Color.white.opacity(0.08))
                                        .cornerRadius(8)
                                    }
                                }
                            }
                            .padding(.vertical, 6)
                            .listRowBackground(Color(red: 0.12, green: 0.12, blue: 0.14))
                        }
                        .onDelete { indexSet in
                            for index in indexSet {
                                storage.deleteRoute(id: storage.savedRoutes[index].id)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Saved Routes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Close") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let url = gpxExportURL {
                    ShareSheet(activityItems: [url])
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
