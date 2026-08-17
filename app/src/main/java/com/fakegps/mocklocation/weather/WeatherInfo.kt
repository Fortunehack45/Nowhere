package com.fakegps.mocklocation.weather

data class CurrentWeather(
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val isDay: Boolean,
    val weatherCode: Int,
    val conditionName: String,
    val conditionEmoji: String
) {
    val temperatureF: Double get() = (temperatureC * 9.0 / 5.0) + 32.0
}

data class DailyForecast(
    val date: String,
    val dayOfWeek: String,
    val maxTempC: Double,
    val minTempC: Double,
    val weatherCode: Int,
    val conditionName: String,
    val conditionEmoji: String
) {
    val maxTempF: Double get() = (maxTempC * 9.0 / 5.0) + 32.0
    val minTempF: Double get() = (minTempC * 9.0 / 5.0) + 32.0
}

data class LocationWeatherReport(
    val latitude: Double,
    val longitude: Double,
    val locationName: String,
    val current: CurrentWeather,
    val forecast: List<DailyForecast>
)
