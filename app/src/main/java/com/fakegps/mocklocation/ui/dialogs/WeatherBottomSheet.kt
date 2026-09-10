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

class WeatherBottomSheet @JvmOverloads constructor(
    private var latitude: Double = 0.0,
    private var longitude: Double = 0.0
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "WeatherBottomSheet"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"

        fun newInstance(lat: Double, lon: Double): WeatherBottomSheet {
            return WeatherBottomSheet(lat, lon).apply {
                arguments = Bundle().apply {
                    putDouble(ARG_LAT, lat)
                    putDouble(ARG_LON, lon)
                }
            }
        }
    }

    private var _binding: LayoutDialogWeatherBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsPrefs: AppSettingsPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (latitude == 0.0 && arguments?.containsKey(ARG_LAT) == true) {
            latitude = arguments?.getDouble(ARG_LAT) ?: 0.0
        }
        if (longitude == 0.0 && arguments?.containsKey(ARG_LON) == true) {
            longitude = arguments?.getDouble(ARG_LON) ?: 0.0
        }
    }

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
            val ctx = context?.applicationContext ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                WeatherManager.fetchWeather(ctx, latitude, longitude, forceRefresh = true)
            }
        }

        // Reactively observe live weather flow
        viewLifecycleOwner.lifecycleScope.launch {
            WeatherManager.weatherFlow.collectLatest { report ->
                if (report != null && _binding != null && isAdded) {
                    renderWeatherReport(report)
                }
            }
        }

        // Initial fetch
        loadWeatherReport()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())
    }

    private fun loadWeatherReport() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val report = WeatherManager.fetchWeather(ctx, latitude, longitude)
            if (_binding != null && isAdded) {
                renderWeatherReport(report)
            }
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
