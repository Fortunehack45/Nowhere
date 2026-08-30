import Foundation
import CoreLocation
import Combine

struct WeatherReport: Codable, Equatable {
    let temperatureCelsius: Double
    let windSpeedKmh: Double
    let relativeHumidity: Int
    let weatherCode: Int
    let conditionText: String
    let conditionEmoji: String

    var temperatureFormatted: String {
        let isImperial = StorageManager.shared.distanceUnit == "IMPERIAL"
        if isImperial {
            let tempF = (temperatureCelsius * 9.0 / 5.0) + 32.0
            return String(format: "%.0f°F", tempF)
        } else {
            return String(format: "%.0f°C", temperatureCelsius)
        }
    }
}

class WeatherService: ObservableObject {

    static let shared = WeatherService()

    @Published var currentWeather: WeatherReport? = nil
    @Published var isLoading: Bool = false

    func fetchWeather(for coordinate: CLLocationCoordinate2D) {
        let lat = coordinate.latitude
        let lon = coordinate.longitude
        guard let url = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(lat)&longitude=\(lon)&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m") else {
            return
        }

        isLoading = true
        URLSession.shared.dataTask(with: url) { [weak self] data, _, error in
            DispatchQueue.main.async {
                self?.isLoading = false
                guard let data = data, error == nil,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let current = json["current"] as? [String: Any],
                      let temp = current["temperature_2m"] as? Double,
                      let humidity = current["relative_humidity_2m"] as? Int,
                      let wind = current["wind_speed_10m"] as? Double,
                      let code = current["weather_code"] as? Int else {
                    return
                }

                let (text, emoji) = self?.interpretWeatherCode(code) ?? ("Clear", "☀️")
                self?.currentWeather = WeatherReport(
                    temperatureCelsius: temp,
                    windSpeedKmh: wind,
                    relativeHumidity: humidity,
                    weatherCode: code,
                    conditionText: text,
                    conditionEmoji: emoji
                )
            }
        }.resume()
    }

    private func interpretWeatherCode(_ code: Int) -> (String, String) {
        switch code {
        case 0: return ("Clear Sky", "☀️")
        case 1, 2: return ("Partly Cloudy", "⛅")
        case 3: return ("Overcast", "☁️")
        case 45, 48: return ("Foggy", "🌫️")
        case 51, 53, 55: return ("Drizzle", "🌦️")
        case 61, 63, 65: return ("Rain", "🌧️")
        case 71, 73, 75: return ("Snowfall", "🌨️")
        case 80, 81, 82: return ("Rain Showers", "🌧️")
        case 95, 96, 99: return ("Thunderstorm", "⛈️")
        default: return ("Clear", "☀️")
        }
    }
}
