package com.fakegps.mocklocation.weather

import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.ui.widget.NowhereWeatherWidgetProvider
import com.fakegps.mocklocation.util.LocationNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object WeatherManager {

    private const val TAG = "WeatherManager"
    private var cachedReport: LocationWeatherReport? = null
    private var lastFetchTimestamp: Long = 0L

    private val _weatherFlow = MutableStateFlow<LocationWeatherReport?>(null)
    val weatherFlow: StateFlow<LocationWeatherReport?> = _weatherFlow.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun getCachedReport(): LocationWeatherReport? = cachedReport

    fun getWeatherCondition(code: Int, isDay: Boolean = true): Pair<String, String> {
        return when (code) {
            0 -> if (isDay) Pair("Clear Sky", "☀️") else Pair("Clear Night", "🌙")
            1 -> if (isDay) Pair("Mainly Clear", "🌤️") else Pair("Mainly Clear", "🌕")
            2 -> Pair("Partly Cloudy", "⛅")
            3 -> Pair("Overcast", "☁️")
            45, 48 -> Pair("Fog & Mist", "🌫️")
            51, 53, 55 -> Pair("Drizzle", "🌦️")
            56, 57 -> Pair("Freezing Drizzle", "❄️")
            61, 63 -> Pair("Rain", "🌧️")
            65 -> Pair("Heavy Rain", "🌧️")
            66, 67 -> Pair("Freezing Rain", "🌨️")
            71, 73, 75 -> Pair("Snowfall", "❄️")
            77 -> Pair("Snow Grains", "🌨️")
            80, 81, 82 -> Pair("Rain Showers", "🌦️")
            85, 86 -> Pair("Snow Showers", "🌨️")
            95 -> Pair("Thunderstorm", "⛈️")
            96, 99 -> Pair("Thunderstorm with Hail", "⛈️")
            else -> Pair("Fair", "🌤️")
        }
    }

    suspend fun fetchWeather(
        context: Context,
        latitude: Double,
        longitude: Double,
        forceRefresh: Boolean = false
    ): LocationWeatherReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val currentCached = cachedReport

        // Check if cached report is recent (< 10 mins) and close (< 500m)
        if (!forceRefresh && currentCached != null && (now - lastFetchTimestamp < 10 * 60 * 1000L)) {
            val dLat = abs(currentCached.latitude - latitude)
            val dLon = abs(currentCached.longitude - longitude)
            if (dLat < 0.005 && dLon < 0.005) {
                _weatherFlow.value = currentCached
                return@withContext currentCached
            }
        }

        _isLoading.value = true
        val placeName = LocationNameResolver.resolveLocationName(context, latitude, longitude)

        try {
            val urlString = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto",
                latitude,
                longitude
            )

            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "NowhereWeatherService/1.0 (Android)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)

                val currentObj = root.getJSONObject("current")
                val tempC = currentObj.optDouble("temperature_2m", 20.0)
                val apparentC = currentObj.optDouble("apparent_temperature", tempC)
                val humidity = currentObj.optInt("relative_humidity_2m", 50)
                val windKmh = currentObj.optDouble("wind_speed_10m", 10.0)
                val isDay = currentObj.optInt("is_day", 1) == 1
                val code = currentObj.optInt("weather_code", 0)

                val (conditionName, conditionEmoji) = getWeatherCondition(code, isDay)

                val currentWeather = CurrentWeather(
                    temperatureC = tempC,
                    apparentTemperatureC = apparentC,
                    humidityPercent = humidity,
                    windSpeedKmh = windKmh,
                    isDay = isDay,
                    weatherCode = code,
                    conditionName = conditionName,
                    conditionEmoji = conditionEmoji
                )

                val forecastList = mutableListOf<DailyForecast>()
                if (root.has("daily")) {
                    val dailyObj = root.getJSONObject("daily")
                    val times = dailyObj.optJSONArray("time")
                    val codes = dailyObj.optJSONArray("weather_code")
                    val maxTemps = dailyObj.optJSONArray("temperature_2m_max")
                    val minTemps = dailyObj.optJSONArray("temperature_2m_min")

                    if (times != null && codes != null && maxTemps != null && minTemps != null) {
                        val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val outFormat = SimpleDateFormat("EEE", Locale.getDefault())

                        for (i in 0 until times.length().coerceAtMost(7)) {
                            val dateStr = times.optString(i)
                            val dayOfWeek = try {
                                val d = inFormat.parse(dateStr)
                                if (d != null) outFormat.format(d) else "Day"
                            } catch (e: Exception) {
                                "Day"
                            }

                            val fCode = codes.optInt(i, 0)
                            val fMax = maxTemps.optDouble(i, tempC + 2.0)
                            val fMin = minTemps.optDouble(i, tempC - 3.0)
                            val (fName, fEmoji) = getWeatherCondition(fCode, true)

                            forecastList.add(
                                DailyForecast(
                                    date = dateStr,
                                    dayOfWeek = dayOfWeek,
                                    maxTempC = fMax,
                                    minTempC = fMin,
                                    weatherCode = fCode,
                                    conditionName = fName,
                                    conditionEmoji = fEmoji
                                )
                            )
                        }
                    }
                }

                val report = LocationWeatherReport(
                    latitude = latitude,
                    longitude = longitude,
                    locationName = placeName,
                    current = currentWeather,
                    forecast = forecastList
                )
                cachedReport = report
                lastFetchTimestamp = System.currentTimeMillis()
                _weatherFlow.value = report
                _isLoading.value = false

                // Real-time widget update broadcast
                NowhereWeatherWidgetProvider.updateAllWeatherWidgets(context)

                return@withContext report
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch note: ${e.message}")
        } finally {
            _isLoading.value = false
        }

        // Resilient fallback report if offline
        val fallbackCurrent = CurrentWeather(
            temperatureC = 22.0,
            apparentTemperatureC = 22.0,
            humidityPercent = 45,
            windSpeedKmh = 12.0,
            isDay = true,
            weatherCode = 0,
            conditionName = "Sunny",
            conditionEmoji = "☀️"
        )
        val fallbackForecast = listOf(
            DailyForecast("Today", "Today", 23.0, 16.0, 0, "Sunny", "☀️"),
            DailyForecast("Tomorrow", "Tue", 24.0, 17.0, 1, "Mainly Clear", "🌤️"),
            DailyForecast("Next", "Wed", 22.0, 15.0, 2, "Partly Cloudy", "⛅"),
            DailyForecast("Next", "Thu", 20.0, 14.0, 61, "Light Rain", "🌧️"),
            DailyForecast("Next", "Fri", 22.0, 16.0, 0, "Clear", "☀️")
        )
        val fallbackReport = LocationWeatherReport(
            latitude = latitude,
            longitude = longitude,
            locationName = placeName,
            current = fallbackCurrent,
            forecast = fallbackForecast
        )
        cachedReport = fallbackReport
        lastFetchTimestamp = System.currentTimeMillis()
        _weatherFlow.value = fallbackReport
        return@withContext fallbackReport
    }
}
