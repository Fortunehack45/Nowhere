package com.fakegps.mocklocation.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        sessionPrefs = SessionPreferences(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadInitialValues()
        setupListeners()
    }

    private fun loadInitialValues() {
        // Advanced Simulation
        binding.switchFusedProvider.isChecked = settingsPrefs.useFusedProvider
        binding.switchJitter.isChecked = settingsPrefs.randomizeJitter
        binding.sliderJitterRadius.value = settingsPrefs.jitterRadiusMeters.coerceIn(0.5f, 10.0f)
        binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", settingsPrefs.jitterRadiusMeters)

        when (settingsPrefs.truncateDecimals) {
            6 -> binding.rbTruncate6.isChecked = true
            4 -> binding.rbTruncate4.isChecked = true
            else -> binding.rbTruncateFull.isChecked = true
        }

        // Accuracy & Altitude & Timing
        binding.sliderAccuracy.value = settingsPrefs.baseAccuracy.coerceIn(0.5f, 20.0f)
        binding.tvAccuracyLabel.text = String.format("Reported Accuracy: %.1f m", settingsPrefs.baseAccuracy)

        binding.sliderAltitude.value = settingsPrefs.defaultAltitude.coerceIn(0.0f, 500.0f)
        binding.tvAltitudeLabel.text = String.format("Default Altitude: %.0f m", settingsPrefs.defaultAltitude)

        binding.switchRandomizeAltitude.isChecked = settingsPrefs.randomizeAltitude

        binding.sliderMovingInterval.value = (settingsPrefs.updateIntervalMovingMs.toFloat()).coerceIn(200.0f, 3000.0f)
        binding.tvMovingIntervalLabel.text = String.format("Moving Rate: %d ms", settingsPrefs.updateIntervalMovingMs)

        // Map Tiles & Visuals
        when (settingsPrefs.mapTileSource) {
            "TOPO" -> binding.rbTopo.isChecked = true
            "WIKIMEDIA" -> binding.rbWikimedia.isChecked = true
            "USGS_SAT" -> binding.rbUsgsSat.isChecked = true
            else -> binding.rbMapnik.isChecked = true
        }
        binding.switchMapAnimations.isChecked = settingsPrefs.enableMapAnimations

        // Theme & Units
        when (settingsPrefs.appTheme) {
            "LIGHT" -> binding.rbThemeLight.isChecked = true
            "SYSTEM" -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        when (settingsPrefs.distanceUnit) {
            "IMPERIAL" -> binding.rbUnitImperial.isChecked = true
            else -> binding.rbUnitMetric.isChecked = true
        }

        // Haptics
        binding.switchHaptics.isChecked = settingsPrefs.enableHapticFeedback
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        // Persistent Location Injector
        binding.switchSettingsBootInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        binding.switchSettingsBootInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Enabled" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Switches & Sliders
        binding.switchFusedProvider.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.useFusedProvider = isChecked
        }

        binding.switchJitter.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.randomizeJitter = isChecked
        }

        binding.sliderJitterRadius.addOnChangeListener { _, value, _ ->
            settingsPrefs.jitterRadiusMeters = value
            binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", value)
        }

        binding.rgTruncate.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.truncateDecimals = when (checkedId) {
                R.id.rbTruncate6 -> 6
                R.id.rbTruncate4 -> 4
                else -> -1
            }
        }

        binding.sliderAccuracy.addOnChangeListener { _, value, _ ->
            settingsPrefs.baseAccuracy = value
            binding.tvAccuracyLabel.text = String.format("Reported Accuracy: %.1f m", value)
        }

        binding.sliderAltitude.addOnChangeListener { _, value, _ ->
            settingsPrefs.defaultAltitude = value
            binding.tvAltitudeLabel.text = String.format("Default Altitude: %.0f m", value)
        }

        binding.switchRandomizeAltitude.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.randomizeAltitude = isChecked
        }

        binding.sliderMovingInterval.addOnChangeListener { _, value, _ ->
            val interval = value.toLong()
            settingsPrefs.updateIntervalMovingMs = interval
            binding.tvMovingIntervalLabel.text = String.format("Moving Rate: %d ms", interval)
        }

        binding.rgMapSource.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.mapTileSource = when (checkedId) {
                R.id.rbTopo -> "TOPO"
                R.id.rbWikimedia -> "WIKIMEDIA"
                R.id.rbUsgsSat -> "USGS_SAT"
                else -> "MAPNIK"
            }
        }

        binding.switchMapAnimations.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.enableMapAnimations = isChecked
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbThemeLight -> "LIGHT"
                R.id.rbThemeSystem -> "SYSTEM"
                else -> "DARK"
            }
            if (settingsPrefs.appTheme != theme) {
                settingsPrefs.appTheme = theme
                settingsPrefs.applyTheme(theme)
                Toast.makeText(this, "Theme set to $theme", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.distanceUnit = when (checkedId) {
                R.id.rbUnitImperial -> "IMPERIAL"
                else -> "METRIC"
            }
        }

        binding.switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.enableHapticFeedback = isChecked
        }
    }

    private fun resetToDefaults() {
        settingsPrefs.useFusedProvider = true
        settingsPrefs.randomizeJitter = true
        settingsPrefs.jitterRadiusMeters = 2.0f
        settingsPrefs.truncateDecimals = -1
        settingsPrefs.baseAccuracy = 2.5f
        settingsPrefs.defaultAltitude = 15.0f
        settingsPrefs.randomizeAltitude = true
        settingsPrefs.updateIntervalMovingMs = 1000L
        settingsPrefs.mapTileSource = "MAPNIK"
        settingsPrefs.enableMapAnimations = true
        settingsPrefs.appTheme = "DARK"
        settingsPrefs.distanceUnit = "METRIC"
        settingsPrefs.enableHapticFeedback = true

        loadInitialValues()
    }
}
