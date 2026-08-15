import SwiftUI
import CoreLocation

struct FavoritesSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var storage = StorageManager.shared
    var onSelectLocation: (CLLocationCoordinate2D, String) -> Void

    @State private var showAddDialog: Bool = false
    @State private var newName: String = ""
    @State private var newLat: String = ""
    @State private var newLon: String = ""
    @State private var selectedTag: String = "City"

    let tags = ["City", "Vacation", "Work", "Gaming", "General"]

    var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.08, green: 0.08, blue: 0.10).ignoresSafeArea()

                if storage.favorites.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "star.slash")
                            .font(.system(size: 48))
                            .foregroundColor(.gray)
                        Text("No Pinned Favorites")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Tap + to add your favorite destinations.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }
                } else {
                    List {
                        ForEach(storage.favorites) { item in
                            Button(action: {
                                onSelectLocation(item.coordinate, item.name)
                                presentationMode.wrappedValue.dismiss()
                            }) {
                                HStack(spacing: 12) {
                                    Image(systemName: "mappin.circle.fill")
                                        .font(.system(size: 24))
                                        .foregroundColor(.red)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name)
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundColor(.white)

                                        HStack(spacing: 8) {
                                            Text(String(format: "%.4f°, %.4f°", item.latitude, item.longitude))
                                                .font(.system(size: 11, design: .monospaced))
                                                .foregroundColor(.gray)

                                            Text(item.tag)
                                                .font(.system(size: 9, weight: .bold))
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(Color.red.opacity(0.2))
                                                .foregroundColor(.red)
                                                .cornerRadius(4)
                                        }
                                    }

                                    Spacer()

                                    Image(systemName: "location.fill")
                                        .font(.system(size: 14))
                                        .foregroundColor(.red)
                                }
                                .padding(.vertical, 4)
                            }
                            .listRowBackground(Color(red: 0.12, green: 0.12, blue: 0.14))
                        }
                        .onDelete { indexSet in
                            for index in indexSet {
                                storage.deleteFavorite(id: storage.favorites[index].id)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Pinned Favorites")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Close") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showAddDialog = true }) {
                        Image(systemName: "plus")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.red)
                    }
                }
            }
            .sheet(isPresented: $showAddDialog) {
                addFavoriteSheet
            }
        }
        .preferredColorScheme(.dark)
    }

    private var addFavoriteSheet: some View {
        NavigationView {
            Form {
                Section(header: Text("Location Details").foregroundColor(.gray)) {
                    TextField("Name (e.g. Paris)", text: $newName)
                    TextField("Latitude (-90.0 to 90.0)", text: $newLat)
                        .keyboardType(.numbersAndPunctuation)
                    TextField("Longitude (-180.0 to 180.0)", text: $newLon)
                        .keyboardType(.numbersAndPunctuation)
                }

                Section(header: Text("Tag").foregroundColor(.gray)) {
                    Picker("Tag", selection: $selectedTag) {
                        ForEach(tags, id: \.self) { tag in
                            Text(tag).tag(tag)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("Add Favorite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { showAddDialog = false }
                        .foregroundColor(.gray)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Save") {
                        if let lat = Double(newLat), let lon = Double(newLon), !newName.isEmpty {
                            storage.addFavorite(name: newName, coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), tag: selectedTag)
                            newName = ""
                            newLat = ""
                            newLon = ""
                            showAddDialog = false
                        }
                    }
                    .foregroundColor(.red)
                    .font(.headline)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
