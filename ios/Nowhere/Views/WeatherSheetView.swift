import SwiftUI

struct WeatherSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var weatherService = WeatherService.shared
    @ObservedObject var engine = LocationSimulationEngine.shared

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                if let weather = weatherService.currentWeather {
                    VStack(spacing: 12) {
                        Text(weather.conditionEmoji)
                            .font(.system(size: 64))

                        Text(weather.temperatureFormatted)
                            .font(.system(size: 44, weight: .black))
                            .foregroundColor(.white)

                        Text(weather.conditionText)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.gray)

                        Text(String(format: "Location: %.4f, %.4f", engine.currentCoordinate.latitude, engine.currentCoordinate.longitude))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(.red)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity)
                    .background(Color(white: 0.12))
                    .cornerRadius(20)
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.1), lineWidth: 1))
                    .padding(.horizontal)

                    // Metrics Grid
                    HStack(spacing: 12) {
                        metricTile(title: "Wind Speed", value: String(format: "%.1f km/h", weather.windSpeedKmh), icon: "wind")
                        metricTile(title: "Humidity", value: "\(weather.relativeHumidity)%", icon: "humidity")
                    }
                    .padding(.horizontal)
                } else if weatherService.isLoading {
                    VStack(spacing: 12) {
                        ProgressView().scaleEffect(1.2)
                        Text("Fetching Telemetry from Open-Meteo...")
                            .font(.system(size: 13))
                            .foregroundColor(.gray)
                    }
                    .padding(40)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "cloud.sun.fill")
                            .font(.system(size: 40))
                            .foregroundColor(.gray)
                        Text("No weather data loaded")
                            .font(.system(size: 14))
                            .foregroundColor(.gray)
                        Button("Refresh") {
                            weatherService.fetchWeather(for: engine.currentCoordinate)
                        }
                        .foregroundColor(.red)
                    }
                    .padding(40)
                }

                Spacer()
            }
            .background(Color(white: 0.08).ignoresSafeArea())
            .navigationTitle("Live Weather Telemetry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
            .onAppear {
                weatherService.fetchWeather(for: engine.currentCoordinate)
            }
        }
    }

    private func metricTile(title: String, value: String, icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundColor(.red)
            Text(value)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
            Text(title)
                .font(.system(size: 11))
                .foregroundColor(.gray)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Color(white: 0.12))
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.1), lineWidth: 1))
    }
}
