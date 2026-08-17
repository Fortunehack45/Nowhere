package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.databinding.LayoutDialogWeatherBinding
import com.fakegps.mocklocation.weather.LocationWeatherReport
import com.fakegps.mocklocation.weather.WeatherManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WeatherBottomSheet(
    private val latitude: Double,
    private val longitude: Double
) : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogWeatherBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsPrefs: AppSettingsPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPrefs = AppSettingsPreferences(requireContext())

        binding.btnRefreshWeather.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                WeatherManager.fetchWeather(requireContext(), latitude, longitude, forceRefresh = true)
            }
        }

        // Reactively observe live weather flow
        viewLifecycleOwner.lifecycleScope.launch {
            WeatherManager.weatherFlow.collectLatest { report ->
                if (report != null) {
                    renderWeatherReport(report)
                }
            }
        }

        // Initial fetch
        loadWeatherReport()
    }

    private fun loadWeatherReport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val report = WeatherManager.fetchWeather(requireContext(), latitude, longitude)
            renderWeatherReport(report)
        }
    }

    private fun renderWeatherReport(report: LocationWeatherReport) {
        if (_binding == null) return

        val useImperial = settingsPrefs.useImperialUnits
        val cur = report.current

        binding.tvWeatherEmoji.text = cur.conditionEmoji
        binding.tvLocationTitle.text = report.locationName
        binding.tvWeatherCondition.text = "${cur.conditionName} • Live Location Weather"

        if (useImperial) {
            binding.tvMainTemperature.text = String.format("%.0f°F", cur.temperatureF)
            binding.tvFeelsLike.text = String.format("Feels like %.0f°F", (cur.apparentTemperatureC * 9.0 / 5.0) + 32.0)
            binding.tvWindSpeed.text = String.format("Wind: %.1f mph", cur.windSpeedKmh * 0.621371)
        } else {
            binding.tvMainTemperature.text = String.format("%.0f°C", cur.temperatureC)
            binding.tvFeelsLike.text = String.format("Feels like %.0f°C", cur.apparentTemperatureC)
            binding.tvWindSpeed.text = String.format("Wind: %.1f km/h", cur.windSpeedKmh)
        }

        binding.tvHumidity.text = "Humidity: ${cur.humidityPercent}%"

        val adapter = ForecastAdapter(report.forecast, useFahrenheit = useImperial)
        binding.rvForecastDays.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvForecastDays.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
