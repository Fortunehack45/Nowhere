import Foundation
import MapKit
import Combine

struct SearchResultItem: Identifiable, Equatable {
    let id = UUID().uuidString
    let title: String
    let subtitle: String
    let coordinate: CLLocationCoordinate2D

    static func == (lhs: SearchResultItem, rhs: SearchResultItem) -> Bool {
        lhs.id == rhs.id
    }
}

class SearchService: ObservableObject {

    static let shared = SearchService()

    @Published var searchResults: [SearchResultItem] = []
    @Published var isSearching: Bool = false

    private var currentSearch: MKLocalSearch?

    func search(query: String, region: MKCoordinateRegion? = nil) {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            searchResults = []
            return
        }

        currentSearch?.cancel()
        isSearching = true

        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        if let region = region {
            request.region = region
        }

        let search = MKLocalSearch(request: request)
        currentSearch = search

        search.start { [weak self] response, error in
            DispatchQueue.main.async {
                self?.isSearching = false
                guard let mapItems = response?.mapItems, error == nil else {
                    self?.searchResults = []
                    return
                }

                self?.searchResults = mapItems.map { item in
                    SearchResultItem(
                        title: item.name ?? "Unknown Location",
                        subtitle: item.placemark.title ?? "",
                        coordinate: item.placemark.coordinate
                    )
                }
            }
        }
    }

    func reverseGeocode(coordinate: CLLocationCoordinate2D, completion: @escaping (String) -> Void) {
        let geocoder = CLGeocoder()
        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)

        geocoder.reverseGeocodeLocation(location) { placemarks, error in
            if let pm = placemarks?.first {
                let city = pm.locality ?? pm.subAdministrativeArea ?? pm.administrativeArea ?? ""
                let country = pm.country ?? ""
                if !city.isEmpty && !country.isEmpty {
                    completion("\(city), \(country)")
                } else if !country.isEmpty {
                    completion(country)
                } else if !city.isEmpty {
                    completion(city)
                } else {
                    completion(String(format: "%.4f°, %.4f°", coordinate.latitude, coordinate.longitude))
                }
            } else {
                completion(String(format: "%.4f°, %.4f°", coordinate.latitude, coordinate.longitude))
            }
        }
    }
}
